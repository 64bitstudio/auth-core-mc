package com.mcortes.authcoremc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.List;
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

    protected IdentityClient() {
        // JPA
    }

    public IdentityClient(
            Tenant tenant, String clientId, String clientSecretHash, boolean firstParty, List<String> redirectUris) {
        this.tenant = tenant;
        this.clientId = clientId;
        this.clientSecretHash = clientSecretHash;
        this.firstParty = firstParty;
        this.redirectUris = redirectUris;
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
}
