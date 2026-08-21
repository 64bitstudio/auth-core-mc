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
}
