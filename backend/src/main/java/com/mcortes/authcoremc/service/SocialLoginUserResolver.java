package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.ExternalIdentity;
import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.ExternalIdentityRepository;
import com.mcortes.authcoremc.repository.UserRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The `app_user`/`external_identity` half of ticket 037 (HU-1/HU-2,
 * docs/definiciones/login-social-real.md, Diseño técnico decisión 3):
 * resolves a confirmed social profile to the {@link User} the caller
 * ({@code SocialLoginSuccessHandler}) should log in as, creating or linking
 * as needed. Kept as its own {@code @Transactional} service — not inlined
 * into the handler — so a failure partway through (e.g. the {@code
 * external_identity} insert) rolls back any {@code app_user} row already
 * written in the same attempt; an {@code AuthenticationSuccessHandler}
 * itself isn't a transactional boundary Spring manages for you.
 */
@Service
public class SocialLoginUserResolver {

    private static final Logger LOG = LoggerFactory.getLogger(SocialLoginUserResolver.class);

    private final UserRepository userRepository;
    private final ExternalIdentityRepository externalIdentityRepository;

    public SocialLoginUserResolver(
            UserRepository userRepository, ExternalIdentityRepository externalIdentityRepository) {
        this.userRepository = userRepository;
        this.externalIdentityRepository = externalIdentityRepository;
    }

    /**
     * @throws SocialLoginBlockedException if {@code profile}'s email matches
     *     an existing {@code app_user} but the provider does NOT report it
     *     as verified. Not addressed explicitly by the ticket/definition —
     *     inferred from Decisión 3's R-1 mitigation (never trust an
     *     unverified email enough to touch someone else's account) plus the
     *     {@code app_user_tenant_email_unique} DB constraint, which makes
     *     "create a second account with the same email" impossible anyway.
     *     Flagged explicitly in the ticket-037 report for Product Owner
     *     confirmation.
     */
    @Transactional
    public User resolve(Tenant tenant, IdentityProviderType provider, SocialProfile profile) {
        // Fast path: a returning social user — this exact provider identity
        // is already linked. Skips the email-verified gate entirely (that
        // gate only governs NEW links, see below) and is the common case for
        // every login after the first, so it's checked first rather than
        // relying on catching the UNIQUE(user_id, provider) violation below
        // for the normal path (the catch below still exists, for races/
        // defense in depth — see its own comment).
        Optional<ExternalIdentity> existingLink = externalIdentityRepository.findByTenantAndProviderAndProviderUserId(
                tenant, provider, profile.providerUserId());
        if (existingLink.isPresent()) {
            return existingLink.get().getUser();
        }

        Optional<User> existingUser = userRepository.findByTenantAndEmail(tenant, profile.email());
        if (existingUser.isPresent()) {
            return linkToExistingUser(tenant, provider, profile, existingUser.get());
        }
        return createUser(tenant, provider, profile);
    }

    private User linkToExistingUser(Tenant tenant, IdentityProviderType provider, SocialProfile profile, User user) {
        if (!profile.emailVerified()) {
            // HU-2 only authorizes auto-linking when the provider vouches for
            // the email (R-1). We can't silently create a second account
            // either (app_user_tenant_email_unique) — the only safe move is
            // to refuse and tell the user why.
            throw new SocialLoginBlockedException(
                    "social_login_email_conflict",
                    "This email is already registered and the provider did not report it as verified — "
                            + "refusing to auto-link. Sign in with your password, or retry with a provider "
                            + "account whose email is verified.");
        }
        linkIdentity(tenant, user, provider, profile.providerUserId());
        return user;
    }

    private User createUser(Tenant tenant, IdentityProviderType provider, SocialProfile profile) {
        String nombre = firstNonBlank(profile.givenName(), localPart(profile.email()));
        String apellidos = profile.familyName() == null ? "" : profile.familyName();
        User user = new User(tenant, profile.email(), null, nombre, apellidos, null);
        if (profile.emailVerified()) {
            user.markEmailVerified();
        }
        userRepository.save(user);
        linkIdentity(tenant, user, provider, profile.providerUserId());
        return user;
    }

    private void linkIdentity(Tenant tenant, User user, IdentityProviderType provider, String providerUserId) {
        try {
            externalIdentityRepository.save(new ExternalIdentity(tenant, user, provider, providerUserId));
        } catch (DataIntegrityViolationException _) {
            // UNIQUE(user_id, provider) — this provider was already linked to
            // this user (a race with another concurrent login, or a caller
            // that bypassed the fast-path check above). A normal repeat
            // social login, not an error: the ticket is explicit this must
            // not surface as a 500.
            LOG.debug(
                    "external_identity already linked for user={} provider={} — treating as a normal repeat login",
                    user.getId(),
                    provider);
        }
    }

    private static String firstNonBlank(String primary, String fallback) {
        return (primary == null || primary.isBlank()) ? fallback : primary;
    }

    private static String localPart(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }
}
