package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.BreakGlassAction;
import com.mcortes.authcoremc.domain.BreakGlassAuditEvent;
import com.mcortes.authcoremc.domain.BreakGlassOutcome;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.repository.BreakGlassAuditEventRepository;
import com.mcortes.authcoremc.repository.LoginEventRepository;
import com.mcortes.authcoremc.repository.TenantRepository;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.Totp;
import com.mcortes.authcoremc.web.BreakGlassAuthenticationException;
import com.mcortes.authcoremc.web.BreakGlassDiagnosticsResponse;
import com.mcortes.authcoremc.web.BreakGlassDiagnosticsResponse.RecentLoginEvent;
import com.mcortes.authcoremc.web.TenantNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ticket 018: break-glass emergency access — deliberately independent of
 * {@code AuthController}/OAuth2 (no login, no JWT, no {@code
 * AuthenticationService}) so it keeps working if that specific subsystem
 * has a bug or incident. Three factors, all required, none of them a user
 * credential: a pre-shared secret, a standalone TOTP secret (not any
 * user's 2FA), and a source-IP allowlist. Never fails open — if any of
 * the three isn't configured, every call is rejected.
 *
 * <p>Every call is audited, success or failure — see {@link
 * BreakGlassAuditEvent}. The HTTP response never reveals which specific
 * check failed (see {@link BreakGlassAuthenticationException}); the real
 * reason is only ever in the audit trail and the server log, for the
 * team operating this door.
 *
 * <p><b>Known limitation, not yet relevant but must be revisited before any
 * reverse proxy/load balancer sits in front of this app</b>: the IP
 * allowlist is checked against {@code HttpServletRequest.getRemoteAddr()}
 * (see {@code BreakGlassController}), which is the DIRECT TCP peer — behind
 * a reverse proxy that would be the proxy's address, not the real caller's,
 * making the allowlist either always match (if the proxy's IP happens to be
 * allowed) or always reject (otherwise), regardless of who's actually
 * calling. This deployment has no reverse proxy today (dev-infra is a
 * direct connection), so it's correct as written; whoever adds one later
 * MUST also add trusted {@code X-Forwarded-For} handling here, not just
 * assume this still works.
 *
 * <p><b>Deliberately stateless TOTP</b> (no replay-window tracking like
 * {@code TotpService} has via Redis): depending on Redis here would put
 * this emergency door behind the very kind of shared-infra dependency the
 * ticket's own origin flagged as a risk to avoid (see
 * docs/definiciones/panel-administracion-clientes.md, "dependencia
 * circular"). The residual risk — a captured code replayable for the rest
 * of its ~90s window — is accepted explicitly, not silently.
 */
@Service
public class BreakGlassService {

    private static final Logger LOG = LoggerFactory.getLogger(BreakGlassService.class);

    private final String sharedSecret;
    private final String totpSecret;
    private final List<String> allowedIps;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final LoginEventRepository loginEventRepository;
    private final BreakGlassAuditEventRepository auditEventRepository;

    public BreakGlassService(
            @Value("${breakglass.secret:}") String sharedSecret,
            @Value("${breakglass.totp-secret:}") String totpSecret,
            @Value("${breakglass.allowed-ips:}") String allowedIpsCsv,
            TenantRepository tenantRepository,
            UserRepository userRepository,
            LoginEventRepository loginEventRepository,
            BreakGlassAuditEventRepository auditEventRepository) {
        this.sharedSecret = sharedSecret;
        this.totpSecret = totpSecret;
        this.allowedIps = allowedIpsCsv == null || allowedIpsCsv.isBlank()
                ? List.of()
                : Arrays.stream(allowedIpsCsv.split(",")).map(String::trim).toList();
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.loginEventRepository = loginEventRepository;
        this.auditEventRepository = auditEventRepository;
    }

    public BreakGlassDiagnosticsResponse diagnostics(String secret, String totpCode, String operator, String remoteIp) {
        authenticateOrAudit(secret, totpCode, operator, remoteIp, BreakGlassAction.DIAGNOSTICS, null);

        boolean databaseHealthy;
        long tenantCount = 0;
        long activeTenantCount = 0;
        long userCount = 0;
        List<RecentLoginEvent> recentEvents = List.of();
        try {
            tenantCount = tenantRepository.count();
            activeTenantCount = tenantRepository.countByDeactivatedAtIsNull();
            userCount = userRepository.count();
            recentEvents = loginEventRepository.findTop10ByOrderByOccurredAtDesc().stream()
                    .map(e -> new RecentLoginEvent(
                            e.getTenant().getName(), e.getProvider(), e.getOutcome().name(), e.getOccurredAt()))
                    .toList();
            databaseHealthy = true;
        } catch (RuntimeException e) {
            // The whole point of this endpoint is to work even when something
            // else is broken — a DB problem is exactly the kind of thing an
            // operator needs to SEE here, not have this call blow up on.
            LOG.warn("Break-glass diagnostics: database query failed", e);
            databaseHealthy = false;
        }

        audit(operator, remoteIp, BreakGlassAction.DIAGNOSTICS, null, BreakGlassOutcome.SUCCESS, null);
        return new BreakGlassDiagnosticsResponse(databaseHealthy, tenantCount, activeTenantCount, userCount, recentEvents);
    }

    @Transactional
    public void deactivateTenant(UUID tenantId, String secret, String totpCode, String operator, String remoteIp) {
        authenticateOrAudit(secret, totpCode, operator, remoteIp, BreakGlassAction.DEACTIVATE_TENANT, tenantId);

        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow(() -> {
            audit(
                    operator, remoteIp, BreakGlassAction.DEACTIVATE_TENANT, tenantId, BreakGlassOutcome.FAILURE,
                    "tenant not found");
            return new TenantNotFoundException(tenantId);
        });
        tenant.deactivate();
        tenantRepository.save(tenant);

        audit(operator, remoteIp, BreakGlassAction.DEACTIVATE_TENANT, tenantId, BreakGlassOutcome.SUCCESS, null);
    }

    private void authenticateOrAudit(
            String secret, String totpCode, String operator, String remoteIp, BreakGlassAction action, UUID targetTenantId) {
        String failureReason = failureReason(secret, totpCode, remoteIp);
        if (failureReason != null) {
            audit(operator, remoteIp, action, targetTenantId, BreakGlassOutcome.FAILURE, failureReason);
            throw new BreakGlassAuthenticationException();
        }
    }

    private String failureReason(String secret, String totpCode, String remoteIp) {
        if (sharedSecret.isBlank() || totpSecret.isBlank() || allowedIps.isEmpty()) {
            return "not configured";
        }
        if (!allowedIps.contains(remoteIp)) {
            return "IP not allowed";
        }
        if (!constantTimeEquals(sharedSecret, secret)) {
            return "invalid secret";
        }
        if (!Totp.verify(totpSecret, totpCode)) {
            return "invalid TOTP code";
        }
        return null;
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private void audit(
            String operator,
            String remoteIp,
            BreakGlassAction action,
            UUID targetTenantId,
            BreakGlassOutcome outcome,
            String detail) {
        try {
            auditEventRepository.save(
                    new BreakGlassAuditEvent(operator, remoteIp, action, targetTenantId, outcome, detail));
        } catch (RuntimeException e) {
            // Same non-negotiable rule as everywhere else audit matters
            // (see LoginEventRecorder, ticket 015): the DB write is a
            // best-effort second copy — it must never be the reason a real
            // break-glass action fails. Logged (not swallowed silently) so a
            // broken DB-side audit trail is itself visible, not just assumed.
            LOG.error("Break-glass audit DB write failed — falling back to the log line below only", e);
        }
        LOG.warn(
                "BREAK-GLASS operator={} ip={} action={} target={} outcome={} detail={}",
                operator, remoteIp, action, targetTenantId, outcome, detail);
    }
}
