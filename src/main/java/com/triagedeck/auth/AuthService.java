package com.triagedeck.auth;

import com.triagedeck.auth.password.PasswordBlocklist;
import com.triagedeck.auth.token.RefreshTokenRequest;
import com.triagedeck.auth.token.RefreshTokenService;
import com.triagedeck.auth.token.TokenService;
import com.triagedeck.auth.verification.RegistrationLinkService;
import com.triagedeck.common.BusinessException;
import com.triagedeck.common.ErrorCode;
import com.triagedeck.user.AppUser;
import com.triagedeck.user.AppUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final RefreshTokenService refreshTokenService;
    private final RegistrationLinkService registrationLinkService;
    private final PasswordBlocklist passwordBlocklist;
    // 邮箱不存在时拿来做一次"假的"密码比对，见 login
    private final String dummyPasswordHash;

    public AuthService(
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            TokenService tokenService,
            RefreshTokenService refreshTokenService,
            RegistrationLinkService registrationLinkService,
            PasswordBlocklist passwordBlocklist) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.refreshTokenService = refreshTokenService;
        this.registrationLinkService = registrationLinkService;
        this.passwordBlocklist = passwordBlocklist;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-constant-time-login");
    }

    /**
     * 凭邮件里的注册链接注册新用户，返回保存后的 AppUser。邮箱由链接里的 token 决定，建出来的账号可以直接登录。
     * 链接无效抛 INVALID_REGISTRATION_LINK（400），其余错误见 createAccount。
     */
    public AppUser register(RegisterRequest request) {
        String email = registrationLinkService.emailFor(request.token());
        AppUser user = createAccount(email, request.password(), request.name());
        // 账号建好才删 token：前面任何一步不通过（比如密码太常见），链接都还能接着用。
        // 同一个链接同时提交两次也不会建出两个账号，第二个会被邮箱的唯一约束挡住，返回 409
        registrationLinkService.consume(request.token());
        return user;
    }

    /**
     * 用一个已经证明归属的邮箱建账号：注册链接、邀请链接都是发到这个邮箱的，能打开就说明邮箱是他的。
     * 密码太常见抛 WEAK_PASSWORD（400），邮箱已被使用抛 EMAIL_ALREADY_USED（409）。
     *
     * <p>方法本身不加 @Transactional：Argon2 算一次要几十毫秒，在事务外面先算好哈希，
     * 写库只有一条 INSERT，saveAndFlush 自己会开事务，算哈希时不占着数据库连接。
     */
    public AppUser createAccount(String email, String password, String name) {
        // 密码规则见 PasswordBlocklist：除了常见密码，还不能是自己的邮箱、邮箱 @ 前面的部分或名字。
        // 这项检查要用到邮箱，而邮箱来自 token、不在请求里，所以在这里做，不在参数校验里做
        String emailLocalPart = email.substring(0, email.indexOf('@'));
        if (passwordBlocklist.isBlocked(password, email, emailLocalPart, name)) {
            throw new BusinessException(ErrorCode.WEAK_PASSWORD);
        }
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_USED);
        }
        String passwordHash = passwordEncoder.encode(password);
        try {
            // saveAndFlush：强制 INSERT 在这一行执行，唯一约束冲突的异常一定在 try 里抛出
            return userRepository.saveAndFlush(new AppUser(email, passwordHash, name));
        } catch (DataIntegrityViolationException e) {
            // 并发兜底：两个请求同时通过了上面的检查，第二条 INSERT 被数据库唯一约束挡住
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_USED);
        }
    }

    /**
     * 校验邮箱和密码，成功则签发 access token 和 refresh token。
     * 邮箱不存在或密码错误，都抛 INVALID_CREDENTIALS（401）。
     *
     * <p>不加 @Transactional：查用户、签发 refresh token 各自开事务，中间算 Argon2 时不占着数据库连接。
     */
    public TokenResponse login(LoginRequest request) {
        String normalizedEmail = AppUser.normalizeEmail(request.email());
        Optional<AppUser> found = userRepository.findByEmail(normalizedEmail);
        // 邮箱不存在时也做一次密码比对：Argon2 故意算得很慢，如果只有邮箱存在时才比对，
        // "邮箱不存在"的请求会明显更快返回，别人就能靠计时判断哪些邮箱注册过
        String hash = found.map(AppUser::getPasswordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
        AppUser user = found.filter(u -> passwordMatches)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        return TokenResponse.of(tokenService.issueAccessToken(user), refreshTokenService.issue(user.getId()));
    }

    /**
     * 用 refresh token 换一对新的 token，旧的 refresh token 随之作废。
     *
     * <p>这里故意不加 @Transactional：rotate 自己开事务。如果外面再包一层事务，
     * rotate 抛出的异常经过这一层时会把整个事务回滚，"发现 token 被盗、吊销所有 token"就白做了。
     */
    public TokenResponse refresh(RefreshTokenRequest request) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(request.refreshToken());
        return TokenResponse.of(tokenService.issueAccessToken(rotation.userId()), rotation.refreshToken());
    }

    /** 查当前登录的用户。token 有效但用户已经不存在时，抛 USER_NOT_FOUND（404）。 */
    public AppUser currentUser(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    /** 退出登录。access token 是无状态的，只能等它在 15 分钟内自己过期；refresh token 立刻作废。 */
    public void logout(RefreshTokenRequest request) {
        refreshTokenService.revoke(request.refreshToken());
    }
}
