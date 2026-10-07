package com.triagedeck.organization;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Generated;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organization")
@Getter
public class Organization {
    @Id
    @Generated
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String slug;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Organization() {}

    public Organization(String name, String slug) {
        this.name = name;
        this.slug = slug;
    }
}
