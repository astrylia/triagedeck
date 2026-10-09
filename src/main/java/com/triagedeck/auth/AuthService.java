package com.triagedeck.auth;

import com.triagedeck.auth.token.RefreshTokenRequest;
import com.triagedeck.auth.token.RefreshTokenService;
import com.triagedeck.auth.token.TokenService;
import com.triagedeck.auth.verification.EmailVerificationService;
import com.triagedeck.common.BusinessException;
import com.triagedeck.common.ErrorCode;
import com.triagedeck.user.AppUser;
import com.triagedeck.user.AppUserRepository;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final RefreshTokenService refreshTokenService;
    private final EmailVerificationService emailVerificationService;
    private final TransactionTemplate transactionTemplate;
    // 邮箱不存在时拿来做一次"假的"密码比对，见 login
    private final String dummyPasswordHash;

    public AuthService(
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            TokenService tokenService,
            RefreshTokenService refreshTokenService,
            EmailVerificationService emailVerificationService,
            TransactionTemplate transactionTemplate) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.refreshTokenService = refreshTokenService;
        this.emailVerificationService = emailVerificationService;
        this.transactionTemplate = transactionTemplate;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-for-constant-time-login");
    }

    /**
     * 注册新用户，返回保存后的 AppUser，并给注册邮箱发验证邮件（事务提交后才发）。
     * 邮箱已被使用时抛 EMAIL_ALREADY_USED（409）。
     *
     * <p>方法本身不加 @Transactional：Argon2 算一次要几十毫秒，在事务外面先算好哈希，
     * 只有写库这一小段放进事务，算哈希时不占着数据库连接。
     */
    public AppUser register(RegisterRequest request) {
        String normalizedEmail = AppUser.normalizeEmail(request.email());
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_USED);
        }
        String passwordHash = passwordEncoder.encode(request.password());
        return transactionTemplate.execute(status -> {
            AppUser saved;
            try {
                // saveAndFlush：强制 INSERT 在这一行执行，唯一约束冲突的异常一定在 try 里抛出
                saved = userRepository.saveAndFlush(new AppUser(normalizedEmail, passwordHash, request.name()));
            } catch (DataIntegrityViolationException e) {
                // 并发兜底：两个请求同时通过了上面的检查，第二条 INSERT 被数据库唯一约束挡住
                throw new BusinessException(ErrorCode.EMAIL_ALREADY_USED);
            }
            // 和建用户在同一个事务里：验证 token 存不进去，用户也不会建出来
            emailVerificationService.sendVerificationEmail(saved);
            return saved;
        });
    }

    /**
     * 校验邮箱和密码，成功则签发 access token 和 refresh token。
     * 邮箱不存在或密码错误，都抛 INVALID_CREDENTIALS（401）；密码对但邮箱还没验证，抛 EMAIL_NOT_VERIFIED（403）。
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
        // 放在密码校验之后：不知道密码的人，拿不到"这个邮箱注册了但没验证"的信息
        if (!user.isEmailVerified()) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
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
