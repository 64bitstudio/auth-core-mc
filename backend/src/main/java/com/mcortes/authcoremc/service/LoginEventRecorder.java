package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.LoginEvent;
import com.mcortes.authcoremc.domain.LoginOutcome;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.LoginEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Records login attempts for the admin panel's usage metrics (ticket 016).
 * Deliberately best-effort/non-blocking — a failure recording the event
 * must never break a real login (see {@code AuthController#login}), so
 * every path here catches broadly and logs rather than propagating. First
 * use of a logger in this codebase (everywhere else fails loud on purpose
 * — see {@code ResendEmailSender} — but that philosophy doesn't fit an
 * audit trail whose whole point is to not be on the critical path).
 */
@Service
public class LoginEventRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(LoginEventRecorder.class);

    private final LoginEventRepository repository;

    public LoginEventRecorder(LoginEventRepository repository) {
        this.repository = repository;
    }

    public void recordSuccess(Tenant tenant, User user, String provider, long latencyMs) {
        saveEvent(tenant, user, provider, LoginOutcome.SUCCESS, latencyMs);
    }

    public void recordFailure(Tenant tenant, String provider, long latencyMs) {
        saveEvent(tenant, null, provider, LoginOutcome.FAILURE, latencyMs);
    }

    // Not named "record" — Sonar (java:S6213) flags it as a restricted
    // identifier since Java 16 introduced record classes. Found by CI, not
    // anticipated.
    private void saveEvent(Tenant tenant, User user, String provider, LoginOutcome outcome, long latencyMs) {
        try {
            repository.save(new LoginEvent(tenant, user, provider, outcome, (int) latencyMs));
        } catch (RuntimeException e) {
            LOG.warn("Failed to record login_event (tenant={}, provider={}, outcome={}) — login itself was not affected",
                    tenant.getId(), provider, outcome, e);
        }
    }
}
