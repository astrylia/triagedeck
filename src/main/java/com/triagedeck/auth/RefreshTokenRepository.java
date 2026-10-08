package com.triagedeck.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 把一个还没吊销的 token 标记为已吊销，返回改了几行（0 或 1）。
     * "检查没吊销"和"标记吊销"在一条 UPDATE 里完成，两个请求同时用同一个 token 时只有一个能拿到 1。
     */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.id = :id AND t.revokedAt IS NULL")
    int revokeIfActive(UUID id, Instant now);

    /** 吊销这个用户所有还能用的 refresh token（发现 token 可能被盗时用）。 */
    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.userId = :userId AND t.revokedAt IS NULL")
    int revokeAllActiveForUser(UUID userId, Instant now);
}
