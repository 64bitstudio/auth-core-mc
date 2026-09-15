package com.mcortes.authcoremc.oauth2;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.RedisTokenStore;
import com.mcortes.authcoremc.service.ExternalIdentityLinkService;
import com.mcortes.authcoremc.service.LoginEventRecorder;
import com.mcortes.authcoremc.service.ProviderAlreadyLinkedException;
import com.mcortes.authcoremc.service.SocialLoginBlockedException;
import com.mcortes.authcoremc.service.SocialLoginUserResolver;
import com.mcortes.authcoremc.service.SocialProfile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * What happens when Google/Facebook hand back a confirmed identity (ticket
 * 037, HU-1/HU-2, docs/definiciones/login-social-real.md — see the "Login
 * social exitoso" sequence diagram there for the exact step-by-step this
 * follows). Resolves/creates the {@code app_user}, links {@code
 * external_identity}, records a {@code LoginEvent}, and hands the browser a
 * one-time exchange code — never a real token — via {@code
 * /ui/social-callback} (ticket 038 mints the real tokens from that code).
 *
 * <p><b>2FA (OQ-8):</b> the ticket calls for hooking into "the 2FA flow
 * already required at password login" before considering the session
 * complete. That flow does not actually exist: {@code AuthController#login}
 * (the password path) mints tokens unconditionally today — {@code
 * TwoFactorController}'s OTP/TOTP endpoints are a self-service, opt-in
 * mechanism a user can exercise from {@code /ui/cuenta}, never a login-time
 * gate. Per the ticket's own explicit constraint ("no inventes un mecanismo
 * nuevo de 2FA, reutiliza el flujo ya existente"), this handler intentionally
 * does NOT add a new gate here — doing so would make social login stricter
 * than password login, which isn't this ticket's call to make. Flagged
 * explicitly for the Product Owner in the ticket-037 report; a real
 * login-time 2FA gate (if wanted) is a follow-up ticket touching both login
 * paths consistently, not a one-off here.
 */
@Component
public class SocialLoginSuccessHandler implements AuthenticationSuccessHandler {

    /**
     * Ticket 038's {@code SocialExchangeController} consumes codes minted
     * under this purpose via {@code RedisTokenStore.consume(...)} — public
     * (not package-private) specifically so that controller, which lives in
     * {@code com.mcortes.authcoremc.web}, can reference this exact constant
     * instead of duplicating the literal string (one source of truth, never
     * two copies that could drift apart).
     */
    public static final String EXCHANGE_PURPOSE = "social-login-exchange";

    private static final Duration EXCHANGE_CODE_TTL = Duration.ofSeconds(60);

    /**
     * Ticket 039 owns the real template; this only has to produce the right
     * redirect target/query params for it to consume. Used only for clients
     * with {@code hostsOwnLoginUi() == false} — see ticket 055.
     */
    private static final String SOCIAL_CALLBACK_PATH = "/ui/social-callback";

    /**
     * Same placeholder route {@code SocialLoginFailureHandler} uses for the
     * cases that must NOT be tenant-themed — see its Javadoc. Used here only
     * as a last-resort fallback (an unresolvable registrationId at this
     * point would mean Spring authenticated against a ClientRegistration
     * this code can no longer parse, which shouldn't happen in practice).
     */
    private static final String GENERIC_ERROR_PATH = "/ui/social-login-error";

    private static final String LOGIN_PATH = "/ui/login";

    /** Ticket 063 -- nombre del query param de error en el redirect de vuelta al perfil tras intentar vincular un proveedor. */
    private static final String LINK_ERROR_PARAM = "link_error";

    private final IdentityClientRepository identityClientRepository;
    private final SocialLoginUserResolver socialLoginUserResolver;
    private final RedisTokenStore redisTokenStore;
    private final LoginEventRecorder loginEventRecorder;
    private final UserRepository userRepository;
    private final ExternalIdentityLinkService externalIdentityLinkService;

    public SocialLoginSuccessHandler(
            IdentityClientRepository identityClientRepository,
            SocialLoginUserResolver socialLoginUserResolver,
            RedisTokenStore redisTokenStore,
            LoginEventRecorder loginEventRecorder,
            UserRepository userRepository,
            ExternalIdentityLinkService externalIdentityLinkService) {
        this.identityClientRepository = identityClientRepository;
        this.socialLoginUserResolver = socialLoginUserResolver;
        this.redisTokenStore = redisTokenStore;
        this.loginEventRecorder = loginEventRecorder;
        this.userRepository = userRepository;
        this.externalIdentityLinkService = externalIdentityLinkService;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        long startedAt = System.currentTimeMillis();

        if (!(authentication instanceof OAuth2AuthenticationToken oauthToken)) {
            // Defensive only — oauth2Login() always produces this type.
            response.sendRedirect(GENERIC_ERROR_PATH);
            return;
        }

        Optional<SocialRegistrationId> parsed = SocialRegistrationId.parse(oauthToken.getAuthorizedClientRegistrationId());
        Optional<IdentityClient> client = parsed.flatMap(p -> identityClientRepository.findById(p.identityClientId()));
        if (parsed.isEmpty() || client.isEmpty()) {
            // Defensive only — Spring already resolved a ClientRegistration
            // for this exact registrationId to get this far.
            response.sendRedirect(GENERIC_ERROR_PATH);
            return;
        }

        IdentityClient identityClient = client.get();
        Tenant tenant = identityClient.getTenant();
        IdentityProviderType provider = parsed.get().provider();

        // Ticket 063 -- consumido ANTES del chequeo de perfil/email de abajo,
        // para que un intento de vínculo sin permiso de email también
        // aterrice en la pantalla de perfil (no en la de login) con su
        // propio error, y para que la intención vieja nunca sobreviva a un
        // login normal posterior en la misma sesión de navegador.
        Optional<UUID> linkIntentUserId = LinkIntentSession.consume(request);

        SocialProfile profile = extractProfile(authentication.getPrincipal());
        if (profile == null) {
            // HU-1: Facebook without the email permission (or, defensively,
            // an unrecognized principal type) — never invent an identifier.
            if (linkIntentUserId.isPresent()) {
                redirectLinkOutcome(response, identityClient, LINK_ERROR_PARAM, "no_email");
                return;
            }
            long elapsed = System.currentTimeMillis() - startedAt;
            loginEventRecorder.recordFailure(tenant, provider.name(), elapsed);
            redirectToError(response, identityClient, "social_login_no_email");
            return;
        }

        if (linkIntentUserId.isPresent()) {
            handleLinkOutcome(response, identityClient, tenant, provider, profile, linkIntentUserId.get());
            return;
        }

        try {
            User user = socialLoginUserResolver.resolve(tenant, provider, profile);
            long elapsed = System.currentTimeMillis() - startedAt;
            loginEventRecorder.recordSuccess(tenant, user, provider.name(), elapsed);

            String code = redisTokenStore.issue(EXCHANGE_PURPOSE, user.getId().toString(), EXCHANGE_CODE_TTL);
            response.sendRedirect(SocialLoginRedirect.buildUri(identityClient, SOCIAL_CALLBACK_PATH, "code", code));
        } catch (SocialLoginBlockedException e) {
            long elapsed = System.currentTimeMillis() - startedAt;
            loginEventRecorder.recordFailure(tenant, provider.name(), elapsed);
            redirectToError(response, identityClient, e.getCode());
        }
    }

    private static SocialProfile extractProfile(Object principal) {
        if (principal instanceof OidcUser oidcUser) {
            String email = oidcUser.getEmail();
            if (email == null || email.isBlank()) {
                return null;
            }
            boolean verified = Boolean.TRUE.equals(oidcUser.getEmailVerified());
            return new SocialProfile(email, verified, oidcUser.getSubject(), oidcUser.getGivenName(), oidcUser.getFamilyName());
        }
        if (principal instanceof OAuth2User oauth2User) {
            String email = oauth2User.getAttribute("email");
            if (email == null || email.isBlank()) {
                // Facebook only sends this when the user granted the email
                // permission — no fallback identifier, per HU-1.
                return null;
            }
            // Facebook has no email_verified-style claim: it only ever
            // returns `email` once verified on its own side (Decisión 3).
            String providerUserId = oauth2User.getName();
            String givenName = oauth2User.getAttribute("first_name");
            String familyName = oauth2User.getAttribute("last_name");
            return new SocialProfile(email, true, providerUserId, givenName, familyName);
        }
        return null;
    }

    private static void redirectToError(HttpServletResponse response, IdentityClient identityClient, String errorCode)
            throws IOException {
        response.sendRedirect(SocialLoginRedirect.buildUri(identityClient, LOGIN_PATH, "error", errorCode));
    }

    /**
     * Ticket 063 -- a diferencia del login (que mintea tokens vía el código
     * de intercambio), acá la sesión del usuario YA era válida desde antes
     * de salir a Google/Facebook — no hace falta emitir nada, solo
     * confirmar el vínculo y volver a la pantalla de perfil.
     */
    private void handleLinkOutcome(
            HttpServletResponse response,
            IdentityClient identityClient,
            Tenant tenant,
            IdentityProviderType provider,
            SocialProfile profile,
            UUID userId)
            throws IOException {
        Optional<User> user = userRepository.findById(userId);
        if (user.isEmpty()) {
            // Defensivo -- la sesión sobrevivió pero el usuario ya no existe (p. ej. cuenta eliminada, ticket 064).
            redirectLinkOutcome(response, identityClient, LINK_ERROR_PARAM, "user_not_found");
            return;
        }
        try {
            externalIdentityLinkService.link(tenant, user.get(), provider, profile);
            redirectLinkOutcome(response, identityClient, "linked", provider.name().toLowerCase(Locale.ROOT));
        } catch (ProviderAlreadyLinkedException _) {
            redirectLinkOutcome(response, identityClient, LINK_ERROR_PARAM, "already_linked");
        }
    }

    /**
     * A diferencia de {@link SocialLoginRedirect#buildUri} (que aterriza en
     * el {@code redirect_uri} exacto del login social), esto vuelve a la
     * pantalla de perfil del cliente ({@code ownUiOrigin() + "/usuario"}) —
     * un destino distinto en el mismo dominio propio del cliente. Sin
     * dominio propio configurado, cae al login hospedado con el error, un
     * fallback razonable ya que hoy solo galgoth-studio (que sí hostea su
     * propia UI) usa esta función.
     */
    private static void redirectLinkOutcome(
            HttpServletResponse response, IdentityClient identityClient, String paramName, String paramValue)
            throws IOException {
        UriComponentsBuilder builder = identityClient
                .ownUiOrigin()
                .map(origin -> UriComponentsBuilder.fromUriString(origin).path("/usuario"))
                .orElseGet(() ->
                        UriComponentsBuilder.fromPath(LOGIN_PATH).queryParam("client_id", identityClient.getClientId()));
        response.sendRedirect(builder.queryParam(paramName, paramValue).encode().build().toUriString());
    }
}
