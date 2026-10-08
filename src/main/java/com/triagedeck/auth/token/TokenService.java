package com.triagedeck.auth.token;

import com.triagedeck.user.AppUser;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** 签发 access token。token 里只放用户 id（sub），角色每次请求按组织从 membership 查。 */
@Service
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    public TokenService(JwtEncoder jwtEncoder, JwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    public AccessToken issueAccessToken(AppUser user) {
        return issueAccessToken(user.getId());
    }

    public AccessToken issueAccessToken(UUID userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("triagedeck")
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token =
                jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(token, properties.accessTokenTtl().toSeconds());
    }
}
