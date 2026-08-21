package com.mcortes.authcoremc.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.service.UserNotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TenantScopedUserResolverTest {

    @Mock
    private UserRepository userRepository;

    private static Tenant tenantFixture() {
        Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        return tenant;
    }

    @Test
    void resolvesAUserThatBelongsToTheGivenTenant() {
        Tenant tenant = tenantFixture();
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "hash");
        UUID userId = UUID.randomUUID();
        ReflectionTestUtils.setField(user, "id", userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        User resolved = new TenantScopedUserResolver(userRepository).resolve(tenant, userId);

        assertThat(resolved).isEqualTo(user);
    }

    @Test
    void rejectsAUserThatBelongsToADifferentTenant() {
        Tenant ownerTenant = tenantFixture();
        Tenant callerTenant = tenantFixture();
        User user = new User(ownerTenant, "ada@example.com", null, "Ada", "Lovelace", "hash");
        UUID userId = UUID.randomUUID();
        ReflectionTestUtils.setField(user, "id", userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> new TenantScopedUserResolver(userRepository).resolve(callerTenant, userId))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void rejectsAnUnknownUserId() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new TenantScopedUserResolver(userRepository).resolve(tenantFixture(), userId))
                .isInstanceOf(UserNotFoundException.class);
    }
}
