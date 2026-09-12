package com.mcortes.authcoremc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An application registered to request tokens from this service, scoped to
 * one tenant (documented as {@code oauth2_client} in docs/BASE_DE_DATOS.md;
 * named {@code IdentityClient}/{@code identity_client} here on purpose, to
 * avoid clashing with Spring Authorization Server's own default
 * {@code oauth2_registered_client} schema — ticket 007 decides how the two
 * reconcile).
 *
 * <p>{@code isFirstParty} gates the direct (non-redirect) login grant — see
 * ticket 007 and docs/ARQUITECTURA.md decision 3: only clients the tenant
 * owns itself may bypass the Authorization Code redirect.
 */
@Entity
@Table(name = "identity_client")
public class IdentityClient {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "client_id", nullable = false, unique = true)
    private String clientId;

    @Column(name = "client_secret_hash")
    private String clientSecretHash;

    @Column(name = "is_first_party", nullable = false)
    private boolean firstParty;

    @Column(name = "redirect_uris")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> redirectUris;

    // Ticket 048: cliente machine-to-machine (grant client_credentials,
    // sin usuario humano) — ej. mail-core-mc llamando a este servicio
    // para validar su propia identidad de app. false para cualquier
    // cliente existente (login interactivo, Authorization Code + PKCE).
    @Column(name = "is_machine_client", nullable = false)
    private boolean machineClient;

    @Column(name = "scopes", nullable = false)
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> scopes;

    // Ticket 055: si es true, el login social (SocialLoginSuccessHandler/
    // FailureHandler) rebota al redirect_uri de este cliente en vez de a
    // las páginas hospedadas por auth-core-mc (/ui/social-callback,
    // /ui/login). false para cualquier cliente existente — mismo
    // comportamiento de siempre.
    @Column(name = "hosts_own_login_ui", nullable = false)
    private boolean hostsOwnLoginUi;

    protected IdentityClient() {
        // JPA
    }

    /** Compatibilidad: clientes normales (login interactivo), scopes por defecto. */
    public IdentityClient(
            Tenant tenant, String clientId, String clientSecretHash, boolean firstParty, List<String> redirectUris) {
        this(tenant, clientId, clientSecretHash, firstParty, redirectUris, false, List.of("openid", "profile"));
    }

    /** Cliente machine-to-machine o con scopes propios (ticket 048), sin UI propia. */
    public IdentityClient(
            Tenant tenant,
            String clientId,
            String clientSecretHash,
            boolean firstParty,
            List<String> redirectUris,
            boolean machineClient,
            List<String> scopes) {
        this.tenant = tenant;
        this.clientId = clientId;
        this.clientSecretHash = clientSecretHash;
        this.firstParty = firstParty;
        this.redirectUris = redirectUris;
        this.machineClient = machineClient;
        this.scopes = scopes;
        this.hostsOwnLoginUi = false;
    }

    /**
     * Ticket 055: {@code hostsOwnLoginUi} habría hecho el constructor de
     * arriba crecer a 8 parámetros (java:S107, máx. 7) — tercera vez que
     * este entity gana un flag opcional (007→048→055); en vez de seguir
     * apilando parámetros posicionales, este builder es el único lugar que
     * necesita saber sobre {@code hostsOwnLoginUi} en la construcción.
     */
    public static Builder builder(Tenant tenant, String clientId, boolean firstParty, List<String> redirectUris) {
        return new Builder(tenant, clientId, firstParty, redirectUris);
    }

    public static final class Builder {
        private final Tenant tenant;
        private final String clientId;
        private final boolean firstParty;
        private final List<String> redirectUris;
        private String clientSecretHash;
        private boolean machineClient;
        private List<String> scopes = List.of("openid", "profile");
        private boolean hostsOwnLoginUi;

        private Builder(Tenant tenant, String clientId, boolean firstParty, List<String> redirectUris) {
            this.tenant = tenant;
            this.clientId = clientId;
            this.firstParty = firstParty;
            this.redirectUris = redirectUris;
        }

        public Builder clientSecretHash(String clientSecretHash) {
            this.clientSecretHash = clientSecretHash;
            return this;
        }

        public Builder machineClient(boolean machineClient) {
            this.machineClient = machineClient;
            return this;
        }

        public Builder scopes(List<String> scopes) {
            this.scopes = scopes;
            return this;
        }

        public Builder hostsOwnLoginUi(boolean hostsOwnLoginUi) {
            this.hostsOwnLoginUi = hostsOwnLoginUi;
            return this;
        }

        /** Escribe los campos directo (sin pasar por un constructor ancho) — Builder es nested, tiene acceso privado. */
        public IdentityClient build() {
            IdentityClient client = new IdentityClient();
            client.tenant = tenant;
            client.clientId = clientId;
            client.clientSecretHash = clientSecretHash;
            client.firstParty = firstParty;
            client.redirectUris = redirectUris;
            client.machineClient = machineClient;
            client.scopes = scopes;
            client.hostsOwnLoginUi = hostsOwnLoginUi;
            return client;
        }
    }

    public UUID getId() {
        return id;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientSecretHash() {
        return clientSecretHash;
    }

    public boolean isFirstParty() {
        return firstParty;
    }

    public List<String> getRedirectUris() {
        return redirectUris;
    }

    public boolean isMachineClient() {
        return machineClient;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public boolean hostsOwnLoginUi() {
        return hostsOwnLoginUi;
    }

    /**
     * Ticket 055: single source of truth for where the one-time social-login
     * code/error should land for this client — its own {@code redirect_uri}
     * if it hosts its own login UI and one is actually configured, or empty
     * to mean "the hosted pages" ({@code SocialLoginSuccessHandler}/{@code
     * SocialLoginFailureHandler} decide what that means; both call this
     * instead of each repeating the same branch, which the first draft of
     * this ticket had duplicated across both classes).
     */
    public Optional<String> ownLoginUiRedirectUri() {
        if (!hostsOwnLoginUi || redirectUris == null || redirectUris.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(redirectUris.get(0));
    }

    /**
     * Ticket 056: same signal as {@link #ownLoginUiRedirectUri()}, but for
     * callers that need to land on a DIFFERENT path on the client's own
     * domain than the exact OAuth {@code redirect_uri} (e.g.
     * {@code VerificationLinkFactory} building an email-verification link,
     * not a social-login callback) — only the origin (scheme+host+port) of
     * {@code redirect_uris[0]} is reusable there, not its path.
     */
    public Optional<String> ownUiOrigin() {
        return ownLoginUiRedirectUri().map(IdentityClient::originOf);
    }

    private static String originOf(String uri) {
        URI parsed = URI.create(uri);
        String authority = parsed.getPort() == -1 ? parsed.getHost() : parsed.getHost() + ":" + parsed.getPort();
        return parsed.getScheme() + "://" + authority;
    }
}
