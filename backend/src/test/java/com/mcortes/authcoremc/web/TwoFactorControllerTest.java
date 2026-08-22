package com.mcortes.authcoremc.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.TotpNotEnrolledException;
import com.mcortes.authcoremc.domain.TwoFactorMethod;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.oauth2.SocialLoginFailureHandler;
import com.mcortes.authcoremc.oauth2.SocialLoginSuccessHandler;
import com.mcortes.authcoremc.security.SecurityConfig;
import com.mcortes.authcoremc.service.InvalidTokenException;
import com.mcortes.authcoremc.service.OtpService;
import com.mcortes.authcoremc.service.TooManyAttemptsException;
import com.mcortes.authcoremc.service.TotpService;
import com.mcortes.authcoremc.service.TwoFactorPreferenceService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(TwoFactorController.class)
@Import(SecurityConfig.class)
class TwoFactorControllerTest {

    @Autowired
    private MockMvcTester mvc;

    // Ticket 012: SecurityConfig's .oauth2ResourceServer(...) needs a JwtDecoder
    // bean to build the filter chain at all — never stubbed, just satisfies DI.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    // Ticket 036: SecurityConfig's .oauth2Login(...) needs a ClientRegistrationRepository
    // bean to build the filter chain at all — never stubbed, just satisfies DI.
    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;

    // Ticket 037: SecurityConfig's .oauth2Login(...) needs the Social*Handler
    // beans to build the filter chain at all — never stubbed, just satisfies DI.
    @MockitoBean
    private SocialLoginSuccessHandler socialLoginSuccessHandler;

    @MockitoBean
    private SocialLoginFailureHandler socialLoginFailureHandler;

    @MockitoBean
    private ClientContextResolver clientContextResolver;

    @MockitoBean
    private TenantScopedUserResolver userResolver;

    @MockitoBean
    private OtpService otpService;

    @MockitoBean
    private TotpService totpService;

    @MockitoBean
    private TwoFactorPreferenceService preferenceService;

    private final Tenant tenant = new Tenant("Acme", "Acme App", "#0057FF", 900, 2_592_000, 86_400, 3_600, 300);
    private final UUID userId = UUID.randomUUID();
    private final User user = new User(tenant, "ada@example.com", null, "Ada", "Lovelace", "hash");

    private void stubResolution() {
        when(clientContextResolver.resolveTenant("acme-web-app")).thenReturn(tenant);
        when(userResolver.resolve(tenant, userId)).thenReturn(user);
    }

    @Test
    void otpRequestReturns202() {
        stubResolution();

        mvc.post()
                .uri("/api/v1/2fa/otp/request")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\"}")
                .exchange()
                .assertThat()
                .hasStatus(202);

        verify(otpService).requestOtp(user);
    }

    @Test
    void otpRequestReturns429WhenOnCooldown() {
        stubResolution();
        doThrow(new TooManyAttemptsException("cooldown")).when(otpService).requestOtp(user);

        mvc.post()
                .uri("/api/v1/2fa/otp/request")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\"}")
                .exchange()
                .assertThat()
                .hasStatus(429);
    }

    @Test
    void otpVerifyReturns200OnSuccess() {
        stubResolution();

        mvc.post()
                .uri("/api/v1/2fa/otp/verify")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"code\":\"123456\"}")
                .exchange()
                .assertThat()
                .hasStatus(200);

        verify(otpService).verifyOtp(user, "123456");
    }

    @Test
    void otpVerifyReturns400ForAWrongCode() {
        stubResolution();
        doThrow(new InvalidTokenException("wrong")).when(otpService).verifyOtp(any(), any());

        mvc.post()
                .uri("/api/v1/2fa/otp/verify")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"code\":\"000000\"}")
                .exchange()
                .assertThat()
                .hasStatus(400);
    }

    @Test
    void totpEnrollReturnsTheSecret() {
        stubResolution();
        when(totpService.enroll(user)).thenReturn("JBSWY3DPEHPK3PXP");

        mvc.post()
                .uri("/api/v1/2fa/totp/enroll")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\"}")
                .exchange()
                .assertThat()
                .hasStatus(200)
                .bodyText()
                .contains("JBSWY3DPEHPK3PXP");
    }

    @Test
    void totpVerifyReturns200OnSuccess() {
        stubResolution();

        mvc.post()
                .uri("/api/v1/2fa/totp/verify")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"code\":\"123456\"}")
                .exchange()
                .assertThat()
                .hasStatus(200);

        verify(totpService).verify(user, "123456");
    }

    @Test
    void activateMethodReturns200OnSuccess() {
        stubResolution();

        mvc.post()
                .uri("/api/v1/2fa/method")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"method\":\"OTP_EMAIL\"}")
                .exchange()
                .assertThat()
                .hasStatus(200);

        verify(preferenceService).activate(user, TwoFactorMethod.OTP_EMAIL);
    }

    @Test
    void activateMethodReturns400WhenTotpIsNotEnrolled() {
        stubResolution();
        doThrow(new TotpNotEnrolledException()).when(preferenceService).activate(eq(user), eq(TwoFactorMethod.TOTP));

        mvc.post()
                .uri("/api/v1/2fa/method")
                .header("X-Client-Id", "acme-web-app")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"method\":\"TOTP\"}")
                .exchange()
                .assertThat()
                .hasStatus(400);
    }
}
