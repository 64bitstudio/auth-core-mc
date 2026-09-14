package com.mcortes.authcoremc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;
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

    // Ticket 017: Vault-wrapped AES-256 data-key for this tenant's envelope
    // encryption (see TenantSecretEncryptor) — null until the first secret
    // is configured for this tenant (lazy, via ensureWrappedDataKey()), so
    // existing tenants from before this ticket don't need a backfill
    // migration.
    @Column(name = "wrapped_data_key")
    private String wrappedDataKey;

    // Ticket 013: soft delete — null means active. Set by deactivate(), read
    // by ClientContextResolver (blocks new sessions) and TenantPurgeService
    // (physically purges 90 days after this timestamp).
    @Column(name = "deactivated_at")
    private Instant deactivatedAt;

    // Ticket 058: rich per-tenant email branding (see EmailTheme) — all
    // nullable, additive on top of app_name/primary_color (ticket 057). A
    // tenant with heroImageUrl unset gets the simple template instead; see
    // getEmailTheme().
    @Column(name = "email_logo_url")
    private String emailLogoUrl;

    @Column(name = "email_hero_image_url")
    private String emailHeroImageUrl;

    @Column(name = "email_side_image_url")
    private String emailSideImageUrl;

    @Column(name = "email_header_subtitle")
    private String emailHeaderSubtitle;

    @Column(name = "email_header_tagline")
    private String emailHeaderTagline;

    @Column(name = "email_footer_tagline")
    private String emailFooterTagline;

    @Column(name = "email_discord_url")
    private String emailDiscordUrl;

    @Column(name = "email_youtube_url")
    private String emailYoutubeUrl;

    @Column(name = "email_twitter_url")
    private String emailTwitterUrl;

    @Column(name = "email_github_url")
    private String emailGithubUrl;

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

    public String getWrappedDataKey() {
        return wrappedDataKey;
    }

    /** Sets this tenant's wrapped data-key — only ever called once, by {@code TenantSecretEncryptor}'s lazy-generation path, never overwritten afterward. */
    public void setWrappedDataKey(String wrappedDataKey) {
        if (wrappedDataKey == null || wrappedDataKey.isBlank()) {
            throw new IllegalArgumentException("wrappedDataKey must not be blank");
        }
        this.wrappedDataKey = wrappedDataKey;
    }

    public Instant getDeactivatedAt() {
        return deactivatedAt;
    }

    public boolean isActive() {
        return deactivatedAt == null;
    }

    /** Idempotent — deactivating an already-deactivated tenant does not reset its purge clock. */
    public void deactivate() {
        if (deactivatedAt == null) {
            this.deactivatedAt = Instant.now();
        }
    }

    public void reactivate() {
        this.deactivatedAt = null;
    }

    /** Updates the editable fields (ticket 013) — {@code name} is deliberately not editable here, it's this tenant's stable identity in the panel. */
    public void update(
            String appName,
            String primaryColor,
            int accessTokenTtlSeconds,
            int refreshTokenTtlSeconds,
            int emailVerificationTtlSeconds,
            int passwordResetTtlSeconds,
            int otpTtlSeconds) {
        this.appName = appName;
        this.primaryColor = primaryColor;
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
        this.emailVerificationTtlSeconds = emailVerificationTtlSeconds;
        this.passwordResetTtlSeconds = passwordResetTtlSeconds;
        this.otpTtlSeconds = otpTtlSeconds;
    }

    /**
     * Ticket 058: {@link Optional#empty()} means "no rich theme configured
     * for this tenant" — {@code BrandedEmailTemplate} falls back to the
     * simple app_name/primary_color layout. {@code heroImageUrl} is the
     * signal field: a tenant with everything else set but no hero image
     * isn't ready to render the rich layout (the hero is the one element
     * with no sane placeholder).
     */
    public Optional<EmailTheme> getEmailTheme() {
        if (emailHeroImageUrl == null || emailHeroImageUrl.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new EmailTheme(
                emailLogoUrl,
                emailHeroImageUrl,
                emailSideImageUrl,
                emailHeaderSubtitle,
                emailHeaderTagline,
                emailFooterTagline,
                emailDiscordUrl,
                emailYoutubeUrl,
                emailTwitterUrl,
                emailGithubUrl));
    }

    /** Replaces this tenant's email theme wholesale — pass {@code null} for any field the tenant doesn't use. */
    public void updateEmailTheme(EmailTheme theme) {
        this.emailLogoUrl = theme.logoUrl();
        this.emailHeroImageUrl = theme.heroImageUrl();
        this.emailSideImageUrl = theme.sideImageUrl();
        this.emailHeaderSubtitle = theme.headerSubtitle();
        this.emailHeaderTagline = theme.headerTagline();
        this.emailFooterTagline = theme.footerTagline();
        this.emailDiscordUrl = theme.discordUrl();
        this.emailYoutubeUrl = theme.youtubeUrl();
        this.emailTwitterUrl = theme.twitterUrl();
        this.emailGithubUrl = theme.githubUrl();
    }
}
