package com.mcortes.authcoremc.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class IdentityClientRepositoryTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private IdentityClientRepository clientRepository;

    @Test
    void savesAFirstPartyClientWithItsRedirectUris() {
        Tenant tenant =
                tenantRepository.save(new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));

        IdentityClient client = clientRepository.save(new IdentityClient(
                tenant, "acme-web-app", null, true, List.of("https://acme.example.com/callback")));

        IdentityClient found = clientRepository.findByClientId("acme-web-app").orElseThrow();
        assertThat(found.isFirstParty()).isTrue();
        assertThat(found.getRedirectUris()).containsExactly("https://acme.example.com/callback");
        assertThat(found.getId()).isEqualTo(client.getId());
    }

    @Test
    void thirdPartyClientsAreNotFirstParty() {
        Tenant tenant =
                tenantRepository.save(new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));

        clientRepository.save(new IdentityClient(
                tenant, "partner-app", "hashed-secret", false, List.of("https://partner.example.com/callback")));

        IdentityClient found = clientRepository.findByClientId("partner-app").orElseThrow();
        assertThat(found.isFirstParty()).isFalse();
    }

    @Test
    void aClientWithNoMachineFlagOrScopesDefaultsToRegularLoginScopes() {
        // Prueba de la migración V9 (columnas nuevas, aditivas): un
        // cliente creado con el constructor "de siempre" (5 args) debe
        // persistir/leer is_machine_client=false y scopes=[openid,profile]
        // por default, sin que el llamante tenga que saber que las
        // columnas nuevas existen.
        Tenant tenant =
                tenantRepository.save(new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));

        clientRepository.save(new IdentityClient(
                tenant, "legacy-app", null, true, List.of("https://acme.example.com/callback")));

        IdentityClient found = clientRepository.findByClientId("legacy-app").orElseThrow();
        assertThat(found.isMachineClient()).isFalse();
        assertThat(found.getScopes()).containsExactly("openid", "profile");
    }

    @Test
    void savesAndReadsBackAMachineToMachineClientWithCustomScopes() {
        Tenant tenant = tenantRepository.save(
                new Tenant("Plataforma", "Plataforma", "#000000", 3_600, 3_600, 86_400, 3_600, 300));

        clientRepository.save(new IdentityClient(
                tenant, "mail-core-mc", "hashed-secret", false, List.of(), true, List.of("mail:send")));

        IdentityClient found = clientRepository.findByClientId("mail-core-mc").orElseThrow();
        assertThat(found.isMachineClient()).isTrue();
        assertThat(found.getScopes()).containsExactly("mail:send");
    }

    @Test
    void aClientWithNoOwnUiFlagDefaultsToHostedLoginPages() {
        // Prueba de la migración V10 (columna nueva, aditiva): un cliente
        // creado con el constructor de 7 args (existente desde el ticket
        // 048) debe persistir/leer hosts_own_login_ui=false por default.
        Tenant tenant =
                tenantRepository.save(new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));

        clientRepository.save(new IdentityClient(
                tenant,
                "legacy-app-2",
                null,
                true,
                List.of("https://acme.example.com/callback"),
                false,
                List.of("openid", "profile")));

        IdentityClient found = clientRepository.findByClientId("legacy-app-2").orElseThrow();
        assertThat(found.hostsOwnLoginUi()).isFalse();
    }

    @Test
    void savesAndReadsBackAClientThatHostsItsOwnLoginUi() {
        Tenant tenant = tenantRepository.save(
                new Tenant("Galgoth Studio", "Galgoth Studio", "#48e5a0", 900, 2_592_000, 86_400, 3_600, 300));

        clientRepository.save(IdentityClient.builder(
                        tenant, "galgoth-studio", true, List.of("https://studio.galgoth.64bitstudio.com/auth/callback"))
                .hostsOwnLoginUi(true)
                .build());

        IdentityClient found = clientRepository.findByClientId("galgoth-studio").orElseThrow();
        assertThat(found.hostsOwnLoginUi()).isTrue();
    }
}
