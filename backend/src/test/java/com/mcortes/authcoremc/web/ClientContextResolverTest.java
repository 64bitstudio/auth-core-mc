package com.mcortes.authcoremc.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClientContextResolverTest {

    @Mock
    private IdentityClientRepository identityClientRepository;

    @Test
    void resolvesTheTenantOwningTheGivenClientId() {
        Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
        IdentityClient client =
                new IdentityClient(tenant, "acme-web-app", null, true, List.of("https://acme.example.com/callback"));
        when(identityClientRepository.findByClientId("acme-web-app")).thenReturn(Optional.of(client));

        Tenant resolved = new ClientContextResolver(identityClientRepository).resolveTenant("acme-web-app");

        assertThat(resolved).isEqualTo(tenant);
    }

    @Test
    void rejectsAnUnknownClientId() {
        when(identityClientRepository.findByClientId("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new ClientContextResolver(identityClientRepository).resolveTenant("ghost"))
                .isInstanceOf(UnknownClientException.class);
    }
}
