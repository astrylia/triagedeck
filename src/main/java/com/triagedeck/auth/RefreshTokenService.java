package com.triagedeck.auth;

import com.triagedeck.common.BusinessException;
import com.triagedeck.common.ErrorCode;
import com.triagedeck.common.SecureTokens;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * refresh token：access token 只有 15 分钟，过期后前端用 refresh token 换一对新的，不用让用户重新输密码。
 *
 * <p>每个 refresh token 只能用一次（轮换）：用它换新 token 的同时，它自己就被吊销。
 * 如果一个已经用过的 token 又被拿来用，说明它很可能被别人偷走了（正常的前端不会重复使用），
 * 这时把这个用户所有的 refresh token 都吊销，小偷和用户都得重新登录。
 */
@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties properties;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, JwtProperties properties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.properties = properties;
    }

    /** 给用户发一个新的 refresh token，返回原始 token（数据库里只存哈希）。 */
    @Transactional
    public String issue(UUID userId) {
        String token = SecureTokens.generate();
        Instant expiresAt = Instant.now().plus(properties.refreshTokenTtl());
        refreshTokenRepository.save(new RefreshToken(userId, SecureTokens.hash(token), expiresAt));
        return token;
    }

    /**
     * 用旧 token 换一个新的。返回用户 id 和新的原始 token。
     *
     * <p>noRollbackFor：发现重复使用时，"吊销这个用户所有 token"要先写进数据库再抛异常；
     * 默认情况下抛出 RuntimeException 会让整个事务回滚，刚才的吊销也就白做了。
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public Rotation rotate(String token) {
        Instant now = Instant.now();
        RefreshToken current = refreshTokenRepository
                .findByTokenHash(SecureTokens.hash(token))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN));
        if (current.isExpiredAt(now)) {
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        if (refreshTokenRepository.revokeIfActive(current.getId(), now) == 0) {
            // 这个 token 已经被用过（或已退出登录）：当作被盗处理
            refreshTokenRepository.revokeAllActiveForUser(current.getUserId(), now);
            throw new BusinessException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        return new Rotation(current.getUserId(), issue(current.getUserId()));
    }

    /** 退出登录：吊销这个 refresh token。token 不存在或已吊销也不报错，结果都一样是"用不了了"。 */
    @Transactional
    public void revoke(String token) {
        refreshTokenRepository
                .findByTokenHash(SecureTokens.hash(token))
                .ifPresent(current -> refreshTokenRepository.revokeIfActive(current.getId(), Instant.now()));
    }

    public record Rotation(UUID userId, String refreshToken) {}
}
