package com.mcortes.authcoremc.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Ticket 054: unit-level coverage of the comma-separated-list parsing (same
 * convention as {@code BreakGlassService.allowed-ips}) and of the "empty by
 * default = no origin allowed" contract, without needing a full Spring
 * context. {@link CorsConfigurationIntegrationTest} covers the real HTTP
 * behavior end to end.
 */
class CorsConfigTest {

    @Test
    void anEmptyAllowlistMatchesNoOrigin() {
        CorsConfiguration config = configurationFor("");
        assertThat(config.getAllowedOrigins()).isNullOrEmpty();
    }

    @Test
    void whitespaceOnlyIsTreatedAsEmpty() {
        CorsConfiguration config = configurationFor("   ");
        assertThat(config.getAllowedOrigins()).isNullOrEmpty();
    }

    @Test
    void aCommaSeparatedListIsParsedAndTrimmed() {
        CorsConfiguration config =
                configurationFor("https://a.example.com, https://b.example.com ,https://c.example.com");
        assertThat(config.getAllowedOrigins())
                .containsExactly("https://a.example.com", "https://b.example.com", "https://c.example.com");
    }

    /** Ticket 093 de galgoth-studio -- hallazgo real: faltaba en el allowlist, ver docstring de {@link CorsConfig}. */
    @Test
    void theAllowlistIncludesTheCurrentRefreshTokenHeaderUsedByListSessions() {
        CorsConfiguration config = configurationFor("https://a.example.com");
        assertThat(config.getAllowedHeaders()).contains("X-Current-Refresh-Token");
    }

    @Test
    void onlyApiV1PathsGetAConfiguration() {
        CorsConfigurationSource source = new CorsConfig("https://a.example.com").corsConfigurationSource();
        assertThat(source.getCorsConfiguration(apiRequest("/oauth2/jwks"))).isNull();
        assertThat(source.getCorsConfiguration(apiRequest("/ui/login"))).isNull();
    }

    private static CorsConfiguration configurationFor(String allowedOriginsCsv) {
        CorsConfigurationSource source = new CorsConfig(allowedOriginsCsv).corsConfigurationSource();
        return source.getCorsConfiguration(apiRequest("/api/v1/login"));
    }

    private static MockHttpServletRequest apiRequest(String path) {
        return new MockHttpServletRequest("OPTIONS", path);
    }
}
