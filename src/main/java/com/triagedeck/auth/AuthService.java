package com.triagedeck.auth;

import com.triagedeck.auth.token.RefreshTokenRequest;
import com.triagedeck.auth.token.RefreshTokenService;
import com.triagedeck.auth.token.TokenService;
import com.triagedeck.auth.verification.RegistrationCodeService;
import com.triagedeck.common.BusinessException;
import com.triagedeck.common.ErrorCode;
import com.triagedeck.user.AppUser;
import com.triagedeck.user.AppUserRepository;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final RefreshTokenService refreshTokenService;
    private final RegistrationCodeService registrationCodeService;
    // 邮箱不存在时拿来做一次"假的"密码比对，见 login
    private final String dummyPasswordHash;

    public AuthService(
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            TokenService tokenService,
            RefreshTokenService refreshTokenService,
            RegistrationCodeService registrationCodeService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.refreshTokenService = refreshTokenService;
        this.registrationCodeService = registrationCodeService;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-constant-time-login");
    }

    /**
     * 凭邮箱收到的验证码注册新用户，返回保存后的 AppUser。建出来的账号邮箱已经验证过，可以直接登录。
     * 验证码不对抛 INVALID_VERIFICATION_CODE（400），邮箱已被使用抛 EMAIL_ALREADY_USED（409）。
     *
     * <p>方法本身不加 @Transactional：Argon2 算一次要几十毫秒，在事务外面先算好哈希，
     * 写库只有一条 INSERT，saveAndFlush 自己会开事务，算哈希时不占着数据库连接。
     */
    public AppUser register(RegisterRequest request) {
        String normalizedEmail = AppUser.normalizeEmail(request.email());
        // 先核对验证码，再查邮箱是否已注册：没有验证码的人，没法拿这个接口探测邮箱有没有注册过
        registrationCodeService.verifyAndConsume(normalizedEmail, request.code());
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_USED);
        }
        String passwordHash = passwordEncoder.encode(request.password());
        try {
            // saveAndFlush：强制 INSERT 在这一行执行，唯一约束冲突的异常一定在 try 里抛出
            return userRepository.saveAndFlush(new AppUser(normalizedEmail, passwordHash, request.name()));
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

    /** 退出登录。access token 是无状态的，只能等它在 15 分钟内自己过期；refresh token 立刻作废。 */
    public void logout(RefreshTokenRequest request) {
        refreshTokenService.revoke(request.refreshToken());
    }
}
