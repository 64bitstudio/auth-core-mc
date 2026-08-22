package com.mcortes.authcoremc.oauth2;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcortes.authcoremc.domain.IdentityProviderType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SocialRegistrationIdTest {

    @Test
    void parsesAnIdentityClientIdAndProvider() {
        UUID id = UUID.randomUUID();

        SocialRegistrationId parsed = SocialRegistrationId.parse(id + "::google").orElseThrow();

        assertThat(parsed.identityClientId()).isEqualTo(id);
        assertThat(parsed.provider()).isEqualTo(IdentityProviderType.GOOGLE);
    }

    @Test
    void isCaseInsensitiveOnTheProviderPart() {
        UUID id = UUID.randomUUID();

        assertThat(SocialRegistrationId.parse(id + "::FaceBook").orElseThrow().provider())
                .isEqualTo(IdentityProviderType.FACEBOOK);
    }

    @Test
    void isEmptyForNull() {
        assertThat(SocialRegistrationId.parse(null)).isEmpty();
    }

    @Test
    void isEmptyWithoutASeparator() {
        assertThat(SocialRegistrationId.parse("not-a-valid-id")).isEmpty();
    }

    @Test
    void isEmptyForAnInvalidUuid() {
        assertThat(SocialRegistrationId.parse("not-a-uuid::google")).isEmpty();
    }

    @Test
    void isEmptyForAnUnsupportedProvider() {
        assertThat(SocialRegistrationId.parse(UUID.randomUUID() + "::twitter")).isEmpty();
    }

    @Test
    void parsesAppleSinceItOnlyFiltersOutCommonOAuth2ProviderMappingElsewhere() {
        // SocialRegistrationId itself has no opinion on which providers are
        // actually supported end-to-end — that filtering (APPLE excluded)
        // lives in TenantAwareClientRegistrationRepository, which needs a
        // CommonOAuth2Provider mapping this class doesn't know about.
        assertThat(SocialRegistrationId.parse(UUID.randomUUID() + "::apple")).isPresent();
    }
}
