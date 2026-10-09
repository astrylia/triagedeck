package com.triagedeck.auth;

import com.triagedeck.auth.token.RefreshTokenRequest;
import com.triagedeck.auth.verification.RegistrationCodeService;
import com.triagedeck.auth.verification.SendRegistrationCodeRequest;
import com.triagedeck.common.BusinessException;
import com.triagedeck.common.ErrorCode;
import com.triagedeck.ratelimit.RateLimitProperties;
import com.triagedeck.ratelimit.RateLimiter;
import com.triagedeck.user.AppUser;
import com.triagedeck.user.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final AuthService authService;
    private final RegistrationCodeService registrationCodeService;
    private final AppUserRepository userRepository;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties limits;

    public AuthController(
            AuthService authService,
            RegistrationCodeService registrationCodeService,
            AppUserRepository userRepository,
            RateLimiter rateLimiter,
            RateLimitProperties limits) {
        this.authService = authService;
        this.registrationCodeService = registrationCodeService;
        this.userRepository = userRepository;
        this.rateLimiter = rateLimiter;
        this.limits = limits;
    }

    /**
     * 注册第一步：给邮箱发 6 位验证码。不管邮箱是否已注册、是否还在 60 秒冷却里，都返回 204，
     * 没法用它探测谁注册过（已注册的邮箱收到的是"你已经有账号了"的提醒，不是验证码）。
     */
    @PostMapping("/api/auth/registration-code")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sendRegistrationCode(@Valid @RequestBody SendRegistrationCodeRequest request, HttpServletRequest http) {
        // 同一个邮箱另有 60 秒冷却；这里再按 IP 限制，防止有人换着邮箱批量触发发信
        rateLimiter.check("registration-code:ip:" + http.getRemoteAddr(), limits.registrationCodePerIp());
        registrationCodeService.sendCode(request.email());
    }

    /** 注册第二步：邮箱 + 验证码 + 密码，验证码对了才创建账号，创建后可以直接登录。 */
    @PostMapping("/api/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        rateLimiter.check("register:ip:" + http.getRemoteAddr(), limits.registerPerIp());
        return UserResponse.from(authService.register(request));
    }

    @PostMapping("/api/auth/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        // 两层：按 IP 挡住一台机器到处试；按邮箱挡住换着 IP 猜同一个账号的密码
        rateLimiter.check("login:ip:" + http.getRemoteAddr(), limits.loginPerIp());
        rateLimiter.check("login:email:" + AppUser.normalizeEmail(request.email()), limits.loginPerEmail());
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
        AppUser user = userRepository
                .findById(UUID.fromString(jwt.getSubject()))
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return UserResponse.from(user);
    }
}
