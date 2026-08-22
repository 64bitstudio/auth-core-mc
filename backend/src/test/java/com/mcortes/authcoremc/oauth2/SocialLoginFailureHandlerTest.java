package com.mcortes.authcoremc.oauth2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import com.mcortes.authcoremc.service.LoginEventRecorder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SocialLoginFailureHandlerTest {

    @Mock
    private IdentityClientRepository identityClientRepository;

    @Mock
    private LoginEventRecorder loginEventRecorder;

    private SocialLoginFailureHandler handler() {
        return new SocialLoginFailureHandler(identityClientRepository, loginEventRecorder);
    }

    private static Tenant tenantFixture() {
        Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
        ReflectionTestUtils.setField(tenant, "id", UUID.randomUUID());
        return tenant;
    }

    private static IdentityClient identityClientFixture(Tenant tenant) {
        IdentityClient client = new IdentityClient(tenant, "acme-web-app", null, true, List.of());
        ReflectionTestUtils.setField(client, "id", UUID.randomUUID());
        return client;
    }

    private static MockHttpServletRequest requestForCallback(String registrationId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/login/oauth2/code/" + registrationId);
        return request;
    }

    @Test
    void consentDeniedRedirectsToTheThemedLoginWithAnErrorAndRecordsTheAttempt() throws Exception {
        Tenant tenant = tenantFixture();
        IdentityClient client = identityClientFixture(tenant);
        String registrationId = client.getId() + "::google";
        when(identityClientRepository.findById(client.getId())).thenReturn(Optional.of(client));

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler().onAuthenticationFailure(
                requestForCallback(registrationId),
                response,
                new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED)));

        assertThat(response.getRedirectedUrl())
                .startsWith("/ui/login")
                .contains("client_id=acme-web-app")
                .contains("error=social_login_cancelled");
        verify(loginEventRecorder).recordFailure(tenant, "GOOGLE", 0);
    }

    @Test
    void consentDeniedFallsBackToTheGenericPageWhenTheRegistrationIdNoLongerResolves() throws Exception {
        UUID ghostId = UUID.randomUUID();
        when(identityClientRepository.findById(ghostId)).thenReturn(Optional.empty());

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler().onAuthenticationFailure(
                requestForCallback(ghostId + "::google"),
                response,
                new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED)));

        assertThat(response.getRedirectedUrl()).isEqualTo(SocialLoginFailureHandler.GENERIC_ERROR_PATH);
        verify(loginEventRecorder, never()).recordFailure(any(), any(), anyLong());
    }

    @Test
    void anUncorrelatedAuthorizationRequestGoesStraightToTheGenericPageWithoutTouchingTenantResolution()
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler().onAuthenticationFailure(
                requestForCallback(UUID.randomUUID() + "::google"),
                response,
                new OAuth2AuthenticationException(new OAuth2Error("authorization_request_not_found")));

        assertThat(response.getRedirectedUrl()).isEqualTo(SocialLoginFailureHandler.GENERIC_ERROR_PATH);
        // Decisión 4: this bucket must never touch tenant resolution at all.
        verifyNoInteractions(identityClientRepository);
        verifyNoInteractions(loginEventRecorder);
    }

    @Test
    void aNonOAuth2AuthenticationExceptionGoesToTheGenericPage() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler().onAuthenticationFailure(
                requestForCallback(UUID.randomUUID() + "::google"), response, new BadCredentialsException("boom"));

        assertThat(response.getRedirectedUrl()).isEqualTo(SocialLoginFailureHandler.GENERIC_ERROR_PATH);
        verifyNoInteractions(identityClientRepository);
    }
}
