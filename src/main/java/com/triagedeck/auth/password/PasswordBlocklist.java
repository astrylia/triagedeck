package com.triagedeck.auth.password;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 密码黑名单：NIST SP 800-63B（第 4 版）要求拒绝"常见的、容易被猜到的、已泄露的"密码。
 * NIST 要求拿整个密码去比较，而不是检查密码里是否包含某个词，所以下面都是整串比较。
 */
@Component
public class PasswordBlocklist {

    private static final String RESOURCE = "security/common-passwords.txt";

    private final Set<String> commonPasswords;

    public PasswordBlocklist() {
        // 列表的来源和生成方式见 common-passwords-NOTICE.txt；读取失败就让应用启动失败
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            commonPasswords = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.isEmpty())
                    .map(line -> line.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load " + RESOURCE, e);
        }
    }

    /**
     * 密码在常见密码列表里，或者就是用户自己的邮箱，返回 true。比较时忽略大小写。
     *
     * <p>为什么单独拦邮箱：想猜某个人密码的人，第一个会试他的邮箱；很多邮箱本身就满 15 位，比如 zhangsan@qq.com。
     *
     * @param email 用户的邮箱，已经转成小写（见 AppUser.normalizeEmail）
     */
    public boolean isBlocked(String password, String email) {
        String candidate = password.toLowerCase(Locale.ROOT);
        return commonPasswords.contains(candidate) || candidate.equals(email);
    }
}
