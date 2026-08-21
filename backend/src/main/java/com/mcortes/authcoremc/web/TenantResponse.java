package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.Tenant;
import java.time.Instant;
import java.util.UUID;

public record TenantResponse(
        UUID id,
        String name,
        String appName,
        String primaryColor,
        int accessTokenTtlSeconds,
        int refreshTokenTtlSeconds,
        int emailVerificationTtlSeconds,
        int passwordResetTtlSeconds,
        int otpTtlSeconds,
        boolean active,
        Instant deactivatedAt,
        Instant createdAt) {

    public static TenantResponse from(Tenant tenant) {
        return new TenantResponse(
                tenant.getId(),
                tenant.getName(),
                tenant.getAppName(),
                tenant.getPrimaryColor(),
                tenant.getAccessTokenTtlSeconds(),
                tenant.getRefreshTokenTtlSeconds(),
                tenant.getEmailVerificationTtlSeconds(),
                tenant.getPasswordResetTtlSeconds(),
                tenant.getOtpTtlSeconds(),
                tenant.isActive(),
                tenant.getDeactivatedAt(),
                tenant.getCreatedAt());
    }
}
