package com.triagedeck.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 纯单元测试：不启动 Spring，直接 new 出来测规则，跑得很快。 */
class PasswordBlocklistTest {

    PasswordBlocklist blocklist = new PasswordBlocklist();

    @Test
    void blocksPasswordsFromCommonListIgnoringCase() {
        assertThat(blocklist.isBlocked("1q2w3e4r5t6y7u8i9o0p")).isTrue();
        assertThat(blocklist.isBlocked("ManchesterUnited")).isTrue();
    }

    @Test
    void blocksSingleRepeatedCharacter() {
        assertThat(blocklist.isBlocked("aaaaaaaaaaaaaaa")).isTrue();
        assertThat(blocklist.isBlocked("密".repeat(15))).isTrue();
    }

    @Test
    void blocksServiceNameAndContextWordsWithDigitsOrSymbolsAdded() {
        assertThat(blocklist.isBlocked("TriageDeck2026!!")).isTrue();
        assertThat(blocklist.isBlocked("alice.smith@acme.com", "alice.smith@acme.com"))
                .isTrue();
        assertThat(blocklist.isBlocked("Alice_Smith_19900101", "Alice Smith")).isTrue();
    }

    @Test
    void comparesWholePasswordNotSubstrings() {
        // 密码里包含名字或服务名，但不是"名字 + 数字符号"的形式，不应该被拒
        assertThat(blocklist.isBlocked("alice-likes-green-tea", "Alice")).isFalse();
        assertThat(blocklist.isBlocked("my triagedeck password")).isFalse();
    }

    @Test
    void contextWordWithoutLettersDoesNotBlockEveryPasswordWithoutLetters() {
        // 邮箱 12345@qq.com 的用户名部分去掉数字后是空串，不能让它匹配所有不含字母的密码
        assertThat(blocklist.isBlocked("8306-2291-7754-1093", "12345")).isFalse();
    }

    @Test
    void allowsOrdinaryPasswords() {
        assertThat(blocklist.isBlocked("correct-horse-battery", "alice@acme.com", "alice", "Alice"))
                .isFalse();
    }
}
