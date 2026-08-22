package com.mcortes.authcoremc.oauth2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.IdentityProviderType;
import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.IdentityClientRepository;
import com.mcortes.authcoremc.security.RedisTokenStore;
import com.mcortes.authcoremc.service.LoginEventRecorder;
import com.mcortes.authcoremc.service.SocialLoginBlockedException;
import com.mcortes.authcoremc.service.SocialLoginUserResolver;
import com.mcortes.authcoremc.service.SocialProfile;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SocialLoginSuccessHandlerTest {

    @Mock
    private IdentityClientRepository identityClientRepository;

    @Mock
    private SocialLoginUserResolver socialLoginUserResolver;

    @Mock
    private RedisTokenStore redisTokenStore;

    @Mock
    private LoginEventRecorder loginEventRecorder;

    private SocialLoginSuccessHandler handler() {
        return new SocialLoginSuccessHandler(
                identityClientRepository, socialLoginUserResolver, redisTokenStore, loginEventRecorder);
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

    private static OAuth2AuthenticationToken googleToken(String registrationId, String email, boolean emailVerified) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token-value")
                .claim("sub", "google-sub-1")
                .claim("email", email)
                .claim("email_verified", emailVerified)
                .claim("given_name", "Ada")
                .claim("family_name", "Lovelace")
                .build();
        DefaultOidcUser principal = new DefaultOidcUser(List.of(), idToken);
        return new OAuth2AuthenticationToken(principal, List.of(), registrationId);
    }

    private static OAuth2AuthenticationToken facebookTokenWithoutEmail(String registrationId) {
        DefaultOAuth2User principal =
                new DefaultOAuth2User(List.of(), Map.of("id", "fb-1", "first_name", "Grace"), "id");
        return new OAuth2AuthenticationToken(principal, List.of(), registrationId);
    }

    @Test
    void redirectsToTheSocialCallbackWithAnExchangeCodeOnSuccess() throws Exception {
        Tenant tenant = tenantFixture();
        IdentityClient client = identityClientFixture(tenant);
        String registrationId = client.getId() + "::google";
        User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", null);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());

        when(identityClientRepository.findById(client.getId())).thenReturn(Optional.of(client));
        when(socialLoginUserResolver.resolve(eq(tenant), eq(IdentityProviderType.GOOGLE), any(SocialProfile.class)))
                .thenReturn(user);
        when(redisTokenStore.issue(
                        SocialLoginSuccessHandler.EXCHANGE_PURPOSE, user.getId().toString(), Duration.ofSeconds(60)))
                .thenReturn("one-time-code");

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler().onAuthenticationSuccess(request, response, googleToken(registrationId, "ada@example.com", true));

        assertThat(response.getRedirectedUrl())
                .startsWith("/ui/social-callback")
                .contains("client_id=acme-web-app")
                .contains("code=one-time-code");
        verify(loginEventRecorder).recordSuccess(eq(tenant), eq(user), eq("GOOGLE"), anyLong());
    }

    @Test
    void blocksFacebookLoginWithoutAnEmailAndDoesNotTouchTheResolver() throws Exception {
        Tenant tenant = tenantFixture();
        IdentityClient client = identityClientFixture(tenant);
        String registrationId = client.getId() + "::facebook";

        when(identityClientRepository.findById(client.getId())).thenReturn(Optional.of(client));

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler().onAuthenticationSuccess(request, response, facebookTokenWithoutEmail(registrationId));

        assertThat(response.getRedirectedUrl())
                .startsWith("/ui/login")
                .contains("client_id=acme-web-app")
                .contains("error=social_login_no_email");
        verify(socialLoginUserResolver, never()).resolve(any(), any(), any());
        verify(loginEventRecorder).recordFailure(eq(tenant), eq("FACEBOOK"), anyLong());
    }

    @Test
    void redirectsToTheThemedLoginWithTheBlockedReasonWhenTheResolverRefuses() throws Exception {
        Tenant tenant = tenantFixture();
        IdentityClient client = identityClientFixture(tenant);
        String registrationId = client.getId() + "::google";

        when(identityClientRepository.findById(client.getId())).thenReturn(Optional.of(client));
        when(socialLoginUserResolver.resolve(eq(tenant), eq(IdentityProviderType.GOOGLE), any(SocialProfile.class)))
                .thenThrow(new SocialLoginBlockedException("social_login_email_conflict", "conflict"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler().onAuthenticationSuccess(request, response, googleToken(registrationId, "ada@example.com", false));

        assertThat(response.getRedirectedUrl())
                .startsWith("/ui/login")
                .contains("error=social_login_email_conflict");
        verify(loginEventRecorder).recordFailure(eq(tenant), eq("GOOGLE"), anyLong());
        verify(redisTokenStore, never()).issue(any(), any(), any());
    }

    @Test
    void fallsBackToTheGenericErrorPageWhenTheRegistrationIdCannotBeResolved() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthenticationToken token = googleToken("not-a-valid-registration-id", "ada@example.com", true);

        handler().onAuthenticationSuccess(request, response, token);

        assertThat(response.getRedirectedUrl()).isEqualTo("/ui/social-login-error");
        verify(loginEventRecorder, never()).recordSuccess(any(), any(), any(), anyLong());
        verify(loginEventRecorder, never()).recordFailure(any(), any(), anyLong());
    }

    @Test
    void fallsBackToTheGenericErrorPageForAnUnexpectedAuthenticationType() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler().onAuthenticationSuccess(
                request, response, new UsernamePasswordAuthenticationToken("someone", null, List.of()));

        assertThat(response.getRedirectedUrl()).isEqualTo("/ui/social-login-error");
    }
}
