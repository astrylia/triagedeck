package com.triagedeck.auth;

import com.triagedeck.auth.link.LoginLinkService;
import com.triagedeck.auth.link.SendLoginLinkRequest;
import com.triagedeck.auth.token.RefreshTokenRequest;
import com.triagedeck.ratelimit.RateLimitProperties;
import com.triagedeck.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final AuthService authService;
    private final LoginLinkService loginLinkService;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties limits;

    public AuthController(
            AuthService authService,
            LoginLinkService loginLinkService,
            RateLimiter rateLimiter,
            RateLimitProperties limits) {
        this.authService = authService;
        this.loginLinkService = loginLinkService;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
    }

    /** 登录第一步：给邮箱发登录链接。邮箱有没有账号都一样处理、都返回 204，没法用它探测谁注册过。 */
    @PostMapping("/api/auth/login-link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sendLoginLink(@Valid @RequestBody SendLoginLinkRequest request, HttpServletRequest http) {
        // 同一个邮箱另有 60 秒冷却；这里再按 IP 限制，防止有人换着邮箱批量触发发信
        rateLimiter.check("login-link:ip:" + http.getRemoteAddr(), limits.loginLinkPerIp());
        loginLinkService.sendLink(request.email());
    }

    /** 登录第二步：链接里的 token 换 access token 和 refresh token。邮箱还没有账号时自动建一个。 */
    @PostMapping("/api/auth/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /** access token 过期后，用 refresh token 换一对新的。不需要带 access token。 */
    @PostMapping("/api/auth/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request);
    }

    /** 退出登录：让这个 refresh token 作废。 */
    @PostMapping("/api/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request);
    }

    /** 当前登录用户。用户 id 来自 token 的 sub。 */
    @GetMapping("/api/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        UUID currentUserId = UUID.fromString(jwt.getSubject());
        return UserResponse.from(authService.currentUser(currentUserId));
    }

    /** 改自己的名字。新账号的名字默认是邮箱 @ 前面的部分。 */
    @PatchMapping("/api/me")
    public UserResponse rename(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RenameRequest request) {
        UUID currentUserId = UUID.fromString(jwt.getSubject());
        return UserResponse.from(authService.rename(currentUserId, request.name()));
    }
}
