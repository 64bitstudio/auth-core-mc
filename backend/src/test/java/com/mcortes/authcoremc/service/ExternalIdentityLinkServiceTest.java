package com.mcortes.authcoremc.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.ExternalIdentity;
import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.ExternalIdentityRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Ticket 069 -- {@link ExternalIdentityLinkService#unlink}, hallazgo real
 * de galgoth-studio#079 (el menú "···" del mockup no tenía ninguna acción
 * real). El resto de este servicio (`link`/`listConnected`) ya está
 * cubierto por {@code AccountLinkProviderControllerTest} end-to-end; el
 * "no te quedes sin forma de entrar" es más claro y rápido como unit test
 * puro, sin necesitar una base de datos real.
 */
@ExtendWith(MockitoExtension.class)
class ExternalIdentityLinkServiceTest {

    @Mock
    private ExternalIdentityRepository externalIdentityRepository;

    private ExternalIdentityLinkService service() {
        return new ExternalIdentityLinkService(externalIdentityRepository);
    }

    private static Tenant tenantFixture() {
        Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        return tenant;
    }

    private static User userFixture(Tenant tenant, String passwordHash) {
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", passwordHash);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    @Test
    void unlinkingAProviderNeverLinkedThrows() {
        Tenant tenant = tenantFixture();
        User user = userFixture(tenant, "hashed-password");
        when(externalIdentityRepository.findByUserAndProvider(user, IdentityProviderType.GOOGLE)).thenReturn(Optional.empty());
        ExternalIdentityLinkService service = service();

        assertThatThrownBy(() -> service.unlink(user, IdentityProviderType.GOOGLE))
                .isInstanceOf(ProviderNotLinkedException.class);
    }

    @Test
    void aUserWithAPasswordCanUnlinkTheirOnlyProvider() {
        Tenant tenant = tenantFixture();
        User user = userFixture(tenant, "hashed-password");
        ExternalIdentity identity = new ExternalIdentity(tenant, user, IdentityProviderType.GOOGLE, "google-sub-1");
        when(externalIdentityRepository.findByUserAndProvider(user, IdentityProviderType.GOOGLE)).thenReturn(Optional.of(identity));
        when(externalIdentityRepository.countByUser(user)).thenReturn(1L);

        service().unlink(user, IdentityProviderType.GOOGLE);

        verify(externalIdentityRepository).delete(identity);
    }

    @Test
    void aSocialOnlyUserCanUnlinkOneOfSeveralProviders() {
        Tenant tenant = tenantFixture();
        User user = userFixture(tenant, null);
        ExternalIdentity identity = new ExternalIdentity(tenant, user, IdentityProviderType.GOOGLE, "google-sub-1");
        when(externalIdentityRepository.findByUserAndProvider(user, IdentityProviderType.GOOGLE)).thenReturn(Optional.of(identity));
        when(externalIdentityRepository.countByUser(user)).thenReturn(2L);

        service().unlink(user, IdentityProviderType.GOOGLE);

        verify(externalIdentityRepository).delete(identity);
    }

    @Test
    void aSocialOnlyUserCannotUnlinkTheirLastProvider() {
        Tenant tenant = tenantFixture();
        User user = userFixture(tenant, null);
        ExternalIdentity identity = new ExternalIdentity(tenant, user, IdentityProviderType.GOOGLE, "google-sub-1");
        when(externalIdentityRepository.findByUserAndProvider(user, IdentityProviderType.GOOGLE)).thenReturn(Optional.of(identity));
        when(externalIdentityRepository.countByUser(user)).thenReturn(1L);
        ExternalIdentityLinkService service = service();

        assertThatThrownBy(() -> service.unlink(user, IdentityProviderType.GOOGLE))
                .isInstanceOf(CannotUnlinkLastLoginMethodException.class);

        verify(externalIdentityRepository, never()).delete(identity);
    }
}
