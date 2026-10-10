package com.triagedeck.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Generated;

@Entity
@Table(name = "app_user")
@Getter
public class AppUser {
    @Id
    @Generated
    private UUID id;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String name;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected AppUser() {}

    public AppUser(String email, String name) {
        this.email = normalizeEmail(email);
        this.name = name;
    }

    public void rename(String name) {
        this.name = name;
    }

    /** 所有地方都要用同一套规则处理邮箱，否则 "Alice@X.com" 建的账号，用 "alice@x.com" 登录时会找不到人。 */
    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
