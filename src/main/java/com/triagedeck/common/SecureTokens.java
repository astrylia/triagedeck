package com.triagedeck.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 随机 token：邀请链接和 refresh token 都用它。原始 token 只交给调用方一次，数据库里只存它的哈希。
 *
 * <p>为什么用 SHA-256 而不是密码那样的 Argon2：token 是 32 字节随机数，不可能被暴力猜出来，
 * 不需要"慢哈希"；而且使用 token 时要按哈希查数据库，Argon2 每次加随机盐，同一个 token 算出的值不一样，没法查。
 */
public final class SecureTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecureTokens() {}

    /** 32 字节随机数，编码成 43 个字符，可以直接放进网址。 */
    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // 每个 JDK 都必须支持 SHA-256，走不到这里
            throw new IllegalStateException(e);
        }
    }
}
