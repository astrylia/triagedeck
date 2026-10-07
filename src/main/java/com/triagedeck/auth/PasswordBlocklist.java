package com.triagedeck.auth;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
    private static final String SERVICE_NAME = "triagedeck";

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
     * @param contextWords 和这个用户相关的词（邮箱、名字等），用户很可能直接拿它们当密码；可以为 null
     */
    public boolean isBlocked(String password, String... contextWords) {
        String candidate = password.toLowerCase(Locale.ROOT);
        if (commonPasswords.contains(candidate)) {
            return true;
        }
        // 同一个字符重复，比如 aaaaaaaaaaaaaaa
        if (candidate.codePoints().distinct().count() == 1) {
            return true;
        }
        // 服务名、邮箱、名字，以及它们的变体：前后加了数字或符号，比如 TriageDeck2026!!
        String candidateLetters = lettersOnly(candidate);
        for (String word : withServiceName(contextWords)) {
            if (candidate.equals(word)) {
                return true;
            }
            String wordLetters = lettersOnly(word);
            // 不含字母的词（比如邮箱 12345@qq.com 里的 12345）去掉数字符号后是空串，不能拿来比较，
            // 否则所有不含字母的密码都会被误拒
            if (!wordLetters.isEmpty() && candidateLetters.equals(wordLetters)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> withServiceName(String... contextWords) {
        List<String> words = new ArrayList<>();
        words.add(SERVICE_NAME);
        for (String word : contextWords) {
            if (word != null) {
                words.add(word.toLowerCase(Locale.ROOT));
            }
        }
        return words;
    }

    private static String lettersOnly(String text) {
        return text.codePoints()
                .filter(Character::isLetter)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}
