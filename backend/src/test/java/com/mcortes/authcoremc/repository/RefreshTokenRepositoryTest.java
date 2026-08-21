package com.mcortes.authcoremc.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcortes.authcoremc.TestcontainersConfiguration;
import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.RefreshToken;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class RefreshTokenRepositoryTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IdentityClientRepository clientRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    void savesAndFindsARefreshTokenByItsHash() {
        Tenant tenant =
                tenantRepository.save(new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        User user = userRepository.save(new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "hash"));
        IdentityClient client = clientRepository.save(
                new IdentityClient(tenant, "acme-web-app", null, true, List.of("https://acme.example.com/callback")));

        RefreshToken token = refreshTokenRepository.save(
                new RefreshToken(user, client, "token-hash-123", Instant.now().plus(30, ChronoUnit.DAYS)));

        RefreshToken found = refreshTokenRepository.findByTokenHash("token-hash-123").orElseThrow();
        assertThat(found.isRevoked()).isFalse();
        assertThat(found.getUser().getId()).isEqualTo(user.getId());
        assertThat(found.getClient().getId()).isEqualTo(client.getId());
        assertThat(found.getId()).isEqualTo(token.getId());
    }

    @Test
    void revokingMarksTheTokenAsRevoked() {
        Tenant tenant =
                tenantRepository.save(new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300));
        User user = userRepository.save(new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "hash"));
        IdentityClient client = clientRepository.save(
                new IdentityClient(tenant, "acme-web-app", null, true, List.of("https://acme.example.com/callback")));
        RefreshToken token = refreshTokenRepository.save(
                new RefreshToken(user, client, "token-hash-456", Instant.now().plus(30, ChronoUnit.DAYS)));

        token.revoke();
        refreshTokenRepository.save(token);

        assertThat(refreshTokenRepository.findByTokenHash("token-hash-456").orElseThrow().isRevoked())
                .isTrue();
    }
}
