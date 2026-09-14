package com.mcortes.authcoremc.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcortes.authcoremc.domain.Tenant;
import org.junit.jupiter.api.Test;

/**
 * Correo con marca por tenant (appName/primaryColor, campos que ya
 * existían para las páginas hospedadas `/ui/**`, reusados aquí en vez de
 * agregar una columna nueva).
 */
class BrandedEmailTemplateTest {

    private static Tenant tenantFixture(String appName, String primaryColor) {
        return new Tenant(appName, appName, primaryColor, 900, 2_592_000, 86_400, 3_600, 300);
    }

    @Test
    void incluyeElNombreYElColorDelTenant() {
        Tenant tenant = tenantFixture("Galgoth Studio", "#48e5a0");

        String html = BrandedEmailTemplate.build(tenant, "Verify your account", "Some body text.", "Verify account", "https://example.com/x");

        assertThat(html).contains("Galgoth Studio").contains("#48e5a0").contains("https://example.com/x").contains("Verify account");
    }

    @Test
    void unColorPrimaryInvalidoUsaElFallbackSeguro() {
        // Dato administrado por un admin, no por un atacante externo -- pero
        // igual no debe llegar sin validar a un atributo HTML.
        Tenant tenant = tenantFixture("Acme", "not-a-color");

        String html = BrandedEmailTemplate.build(tenant, "Heading", "Body", "CTA", "https://example.com");

        assertThat(html).contains("#0057FF").doesNotContain("not-a-color");
    }

    @Test
    void escapaCaracteresHtmlEnElNombreDelTenant() {
        Tenant tenant = tenantFixture("Acme <script>alert(1)</script>", "#0057FF");

        String html = BrandedEmailTemplate.build(tenant, "Heading", "Body", "CTA", "https://example.com");

        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;");
    }
}
