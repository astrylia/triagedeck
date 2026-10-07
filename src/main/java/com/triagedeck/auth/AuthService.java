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

    public AuthService(AppUserRepository userRepository, PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    /**
     * 注册新用户，返回保存后的 AppUser。
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
        AppUser user = new AppUser(normalizedEmail, passwordHash, name);
        try {
            // saveAndFlush：强制 INSERT 在这一行执行，唯一约束冲突的异常一定在 try 里抛出
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // 并发兜底：两个请求同时通过了上面的检查，第二条 INSERT 被数据库唯一约束挡住
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_USED);
        }
    }

    /**
     * 校验邮箱和密码，成功则签发 token。
     * 邮箱不存在或密码错误，都抛 INVALID_CREDENTIALS（401）。
     */
    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        String normalizedEmail = AppUser.normalizeEmail(request.email());
        AppUser user = userRepository
                .findByEmail(normalizedEmail)
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        return tokenService.issueAccessToken(user);
    }
}
