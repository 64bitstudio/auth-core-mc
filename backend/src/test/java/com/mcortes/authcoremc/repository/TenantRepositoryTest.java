package com.mcortes.authcoremc.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class TenantRepositoryTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Test
    void savesAndRetrievesATenantWithItsParametrizableTtls() {
        Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);

        Tenant saved = tenantRepository.save(tenant);
        Tenant found = tenantRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getName()).isEqualTo("Acme");
        assertThat(found.getAppName()).isEqualTo("Acme App");
        assertThat(found.getPrimaryColor()).isEqualTo("#0057FF");
        assertThat(found.getAccessTokenTtlSeconds()).isEqualTo(900);
        assertThat(found.getRefreshTokenTtlSeconds()).isEqualTo(2_592_000);
        assertThat(found.getCreatedAt()).isNotNull();
    }
}
