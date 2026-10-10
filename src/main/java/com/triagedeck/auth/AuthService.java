package com.triagedeck.auth;

import com.triagedeck.auth.link.LoginLinkService;
import com.triagedeck.auth.token.RefreshTokenRequest;
import com.triagedeck.auth.token.RefreshTokenService;
import com.triagedeck.auth.token.TokenService;
import com.triagedeck.common.BusinessException;
import com.triagedeck.common.ErrorCode;
import com.triagedeck.user.AppUser;
import com.triagedeck.user.AppUserRepository;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final AppUserRepository userRepository;
    private final TokenService tokenService;
    private final RefreshTokenService refreshTokenService;
    private final LoginLinkService loginLinkService;

    public AuthService(
            AppUserRepository userRepository,
            TokenService tokenService,
            RefreshTokenService refreshTokenService,
            LoginLinkService loginLinkService) {
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.refreshTokenService = refreshTokenService;
        this.loginLinkService = loginLinkService;
    }

    /**
     * 凭邮件里的登录链接登录，签发 access token 和 refresh token。邮箱还没有账号时顺便建一个。
     * 链接无效（不存在、过期、已用过）抛 INVALID_LOGIN_LINK（400）。
     */
    public TokenResponse login(LoginRequest request) {
        String email = loginLinkService.consume(request.token());
        AppUser user = findOrCreateUser(email);
        return issueTokens(user.getId());
    }

    /**
     * 按邮箱找用户，没有就建一个，名字先用邮箱 @ 前面的部分，之后可以在 PATCH /api/me 里改。
     * 只能用已经证明归属的邮箱调用：登录链接、邀请链接都是发到这个邮箱的，能打开就说明邮箱是他的。
     *
     * <p>不加 @Transactional：查询和 saveAndFlush 各自开事务。同一个新邮箱的两个请求同时到达时，
     * 第二条 INSERT 被邮箱的唯一约束挡住，这时账号已经被第一个请求建好了，再查一次即可。
     */
    public AppUser findOrCreateUser(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            try {
                return userRepository.saveAndFlush(new AppUser(email, email.substring(0, email.indexOf('@'))));
            } catch (DataIntegrityViolationException e) {
                return userRepository.findByEmail(email).orElseThrow();
            }
        });
    }

    /** 给这个用户签发一对新的 access token 和 refresh token。 */
    public TokenResponse issueTokens(UUID userId) {
        return TokenResponse.of(tokenService.issueAccessToken(userId), refreshTokenService.issue(userId));
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

    /** 改当前用户的名字。不用调 save：用户是在这个事务里查出来的，提交时 JPA 会自动 UPDATE。 */
    @Transactional
    public AppUser rename(UUID userId, String name) {
        AppUser user = currentUser(userId);
        user.rename(name);
        return user;
    }

    /** 退出登录。access token 是无状态的，只能等它在 15 分钟内自己过期；refresh token 立刻作废。 */
    public void logout(RefreshTokenRequest request) {
        refreshTokenService.revoke(request.refreshToken());
    }
}
