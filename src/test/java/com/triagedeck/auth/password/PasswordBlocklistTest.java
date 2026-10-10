package com.triagedeck.auth.password;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 纯单元测试：不启动 Spring，直接 new 出来测规则，跑得很快。 */
class PasswordBlocklistTest {

    PasswordBlocklist blocklist = new PasswordBlocklist();

    @Test
    void blocksPasswordsFromCommonListIgnoringCase() {
        assertThat(blocklist.isBlocked("1q2w3e4r5t6y7u8i9o0p", "alice@acme.com"))
                .isTrue();
        assertThat(blocklist.isBlocked("ManchesterUnited", "alice@acme.com")).isTrue();
        // 同一个字符重复这类常见写法，列表里本来就有
        assertThat(blocklist.isBlocked("aaaaaaaaaaaaaaa", "alice@acme.com")).isTrue();
    }

    @Test
    void blocksOwnEmailIgnoringCase() {
        assertThat(blocklist.isBlocked("Alice.Smith@Acme.com", "alice.smith@acme.com"))
                .isTrue();
    }

    @Test
    void comparesWholePasswordNotSubstrings() {
        // 密码里包含邮箱，但不就是邮箱，不应该被拒
        assertThat(blocklist.isBlocked("alice.smith@acme.com is me", "alice.smith@acme.com"))
                .isFalse();
    }

    @Test
    void allowsOrdinaryPasswords() {
        assertThat(blocklist.isBlocked("correct-horse-battery", "alice@acme.com"))
                .isFalse();
    }
}
