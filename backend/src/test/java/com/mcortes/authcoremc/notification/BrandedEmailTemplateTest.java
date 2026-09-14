package com.mcortes.authcoremc.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcortes.authcoremc.domain.EmailTheme;
import com.mcortes.authcoremc.domain.Tenant;
import org.junit.jupiter.api.Test;

/**
 * Correo con marca por tenant. Dos niveles (ticket 058, sobre el 057):
 * simple (solo appName/primaryColor) cuando el tenant no tiene
 * {@link EmailTheme} configurado, rico (header/hero/footer) cuando sí.
 */
class BrandedEmailTemplateTest {

    private static Tenant tenantFixture(String appName, String primaryColor) {
        return new Tenant(appName, appName, primaryColor, 900, 2_592_000, 86_400, 3_600, 300);
    }

    @Test
    void sinThemeUsaElDisenoSimple() {
        Tenant tenant = tenantFixture("Acme", "#0057FF");

        String html = BrandedEmailTemplate.build(tenant, "Verify", "your account", "Some body text.", "Verify account", "https://example.com/x", 24);

        assertThat(html).contains("Acme").contains("#0057FF").contains("https://example.com/x").contains("Verify account");
        // El diseño rico nunca aparece si no hay theme -- nada de header/hero/footer.
        assertThat(html).doesNotContain("¡Hola!");
    }

    @Test
    void unColorPrimaryInvalidoUsaElFallbackSeguro() {
        // Dato administrado por un admin, no por un atacante externo -- pero
        // igual no debe llegar sin validar a un atributo HTML.
        Tenant tenant = tenantFixture("Acme", "not-a-color");

        String html = BrandedEmailTemplate.build(tenant, "Heading", "accent", "Body", "CTA", "https://example.com", 24);

        assertThat(html).contains("#0057FF").doesNotContain("not-a-color");
    }

    @Test
    void escapaCaracteresHtmlEnElNombreDelTenantEnModoSimple() {
        Tenant tenant = tenantFixture("Acme <script>alert(1)</script>", "#0057FF");

        String html = BrandedEmailTemplate.build(tenant, "Heading", "accent", "Body", "CTA", "https://example.com", 24);

        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    void conThemeUsaElDisenoRicoConHeroYFooter() {
        Tenant tenant = tenantFixture("Galgoth Studio", "#48e5a0");
        tenant.updateEmailTheme(new EmailTheme(
                "https://studio.galgoth.64bitstudio.com/email/logo.png",
                "https://studio.galgoth.64bitstudio.com/email/hero.jpg",
                "https://studio.galgoth.64bitstudio.com/email/side.jpg",
                "CREA · EDITA · DA VIDA",
                "Tu mundo.\nTus criaturas.\nSin límites.",
                "Edición. Creatividad. Mundos infinitos.",
                "https://discord.gg/galgoth",
                null,
                null,
                "https://github.com/galgoth"));

        String html = BrandedEmailTemplate.build(
                tenant, "Confirma", "tu correo", "Gracias por unirte a {appName}.", "Confirmar mi correo", "https://example.com/verify", 24);

        assertThat(html)
                .contains("¡Hola!")
                .contains("https://studio.galgoth.64bitstudio.com/email/hero.jpg")
                .contains("https://studio.galgoth.64bitstudio.com/email/side.jpg")
                .contains("CREA · EDITA · DA VIDA")
                .contains("Sin límites.")
                .contains("<strong>Galgoth Studio</strong>")
                .contains("expirará en 24 horas")
                .contains("Discord")
                .contains("GitHub")
                // Sin URL configurada, esos dos no deben aparecer.
                .doesNotContain("YouTube");
    }

    @Test
    void unThemeSinHeroImageSeTrataComoNoConfigurado() {
        // heroImageUrl es la señal de "theme listo" -- el resto sin ella no
        // alcanza para renderizar el layout rico (ver Tenant.getEmailTheme()).
        Tenant tenant = tenantFixture("Acme", "#0057FF");
        tenant.updateEmailTheme(new EmailTheme(
                "https://example.com/logo.png", null, null, "SUBTITLE", null, null, null, null, null, null));

        String html = BrandedEmailTemplate.build(tenant, "Heading", "accent", "Body", "CTA", "https://example.com", 24);

        assertThat(html).doesNotContain("¡Hola!").doesNotContain("SUBTITLE");
    }

    @Test
    void expiryHoursUsaElTtlRealNoUnValorFijo() {
        Tenant tenant = tenantFixture("Galgoth Studio", "#48e5a0");
        tenant.updateEmailTheme(new EmailTheme("https://x/logo.png", "https://x/hero.jpg", null, null, null, null, null, null, null, null));

        String html = BrandedEmailTemplate.build(tenant, "H", "A", "B", "CTA", "https://example.com", 1);

        assertThat(html).contains("expirará en 1 horas").doesNotContain("expirará en 24 horas");
    }
}
