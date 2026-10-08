package com.triagedeck.auth;

import com.triagedeck.common.BusinessException;
import com.triagedeck.common.ErrorCode;
import com.triagedeck.user.AppUser;
import com.triagedeck.user.AppUserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final RefreshTokenService refreshTokenService;
    private final EmailVerificationService emailVerificationService;

    public AuthService(
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            TokenService tokenService,
            RefreshTokenService refreshTokenService,
            EmailVerificationService emailVerificationService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.refreshTokenService = refreshTokenService;
        this.emailVerificationService = emailVerificationService;
    }

    /**
     * 注册新用户，返回保存后的 AppUser，并给注册邮箱发验证邮件（事务提交后才发）。
     * 邮箱已被使用时抛 EMAIL_ALREADY_USED（409）。
     */
    @Transactional
    public AppUser register(RegisterRequest request) {
        String email = request.email();
        String password = request.password();
        String name = request.name();

        String normalizedEmail = AppUser.normalizeEmail(email);
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_USED);
        }
        String passwordHash = passwordEncoder.encode(password);
        AppUser saved;
        try {
            // saveAndFlush：强制 INSERT 在这一行执行，唯一约束冲突的异常一定在 try 里抛出
            saved = userRepository.saveAndFlush(new AppUser(normalizedEmail, passwordHash, name));
        } catch (DataIntegrityViolationException e) {
            // 并发兜底：两个请求同时通过了上面的检查，第二条 INSERT 被数据库唯一约束挡住
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_USED);
        }
        emailVerificationService.sendVerificationEmail(saved);
        return saved;
    }

    /**
     * 校验邮箱和密码，成功则签发 access token 和 refresh token。
     * 邮箱不存在或密码错误，都抛 INVALID_CREDENTIALS（401）。
     */
    @Transactional
    public TokenResponse login(LoginRequest request) {
        String normalizedEmail = AppUser.normalizeEmail(request.email());
        AppUser user = userRepository
                .findByEmail(normalizedEmail)
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
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
