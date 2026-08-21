package com.mcortes.authcoremc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A project/client using this identity service. All business data (users,
 * OAuth2 clients, identity provider config) is partitioned by tenant, so a
 * tenant can later be "exported" to its own dedicated instance without
 * redesigning the schema — see docs/ARQUITECTURA.md, decision 1.
 */
@Entity
@Table(name = "tenant")
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "app_name", nullable = false)
    private String appName;

    @Column(name = "primary_color", nullable = false)
    private String primaryColor;

    @Column(name = "access_token_ttl_seconds", nullable = false)
    private int accessTokenTtlSeconds;

    @Column(name = "refresh_token_ttl_seconds", nullable = false)
    private int refreshTokenTtlSeconds;

    @Column(name = "email_verification_ttl_seconds", nullable = false)
    private int emailVerificationTtlSeconds;

    @Column(name = "password_reset_ttl_seconds", nullable = false)
    private int passwordResetTtlSeconds;

    @Column(name = "otp_ttl_seconds", nullable = false)
    private int otpTtlSeconds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Tenant() {
        // JPA
    }

    public Tenant(
            String name,
            String appName,
            String primaryColor,
            int accessTokenTtlSeconds,
            int refreshTokenTtlSeconds,
            int emailVerificationTtlSeconds,
            int passwordResetTtlSeconds,
            int otpTtlSeconds) {
        this.name = name;
        this.appName = appName;
        this.primaryColor = primaryColor;
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
        this.emailVerificationTtlSeconds = emailVerificationTtlSeconds;
        this.passwordResetTtlSeconds = passwordResetTtlSeconds;
        this.otpTtlSeconds = otpTtlSeconds;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getAppName() {
        return appName;
    }

    public String getPrimaryColor() {
        return primaryColor;
    }

    public int getAccessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    public int getRefreshTokenTtlSeconds() {
        return refreshTokenTtlSeconds;
    }

    public int getEmailVerificationTtlSeconds() {
        return emailVerificationTtlSeconds;
    }

    public int getPasswordResetTtlSeconds() {
        return passwordResetTtlSeconds;
    }

    public int getOtpTtlSeconds() {
        return otpTtlSeconds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
