package com.mcortes.authcoremc.notification;

import com.mcortes.authcoremc.domain.EmailTheme;
import com.mcortes.authcoremc.domain.Tenant;
import java.time.Year;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Builds the HTML body for verification/reset/change-email emails.
 *
 * <p>Two tiers, both keyed off {@link Tenant#getEmailTheme()}:
 *
 * <ul>
 *   <li><b>Rich</b> (ticket 058) — a tenant with a configured
 *       {@link EmailTheme}: full header/hero/footer chrome using that
 *       tenant's art and links. Introduced for galgoth-studio; any other
 *       tenant that configures a theme gets the same layout with its own
 *       art — nothing here is galgoth-studio-specific.
 *   <li><b>Simple</b> (ticket 057) — no theme configured: just
 *       {@code app_name}/{@code primary_color}, the original fallback.
 * </ul>
 *
 * <p>Table-based layout with inline styles throughout — `<style>` blocks
 * are stripped or unreliable in several real email clients (Outlook
 * desktop chief among them). The rich layout's side art uses both the
 * legacy HTML {@code background} attribute (what Outlook actually
 * honors) and a CSS {@code background-image} fallback for everyone else.
 */
public final class BrandedEmailTemplate {

    // Fallback for a tenant whose primary_color isn't a well-formed hex
    // value -- admin-set data, not attacker-controlled, but this embeds
    // straight into an HTML attribute, so a malformed/hostile value
    // shouldn't reach the markup unchecked.
    private static final String DEFAULT_ACCENT = "#0057FF";
    private static final Pattern HEX_COLOR = Pattern.compile("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})$");
    private static final String DARK_BG = "#0b0f14";
    private static final String PANEL_BG = "#111820";
    private static final String BORDER = "#232d38";
    private static final String TEXT_MUTED = "#8a96a3";
    private static final String TEXT_SOFT = "#aab3bd";

    private BrandedEmailTemplate() {}

    /**
     * @param headingPlain first part of the on-brand heading, rendered in white — e.g. "Confirma".
     * @param headingAccent second part, rendered in the tenant's accent color — e.g. "tu correo".
     * @param bodyText one or two sentences of context above the button.
     * @param ctaLabel button text, e.g. "Confirmar mi correo".
     * @param ctaUrl the real link (already built per-client by {@link VerificationLinkFactory}).
     * @param expiryHours the REAL configured TTL for this link, in whole hours — never a hardcoded guess.
     */
    public static String build(
            Tenant tenant, String headingPlain, String headingAccent, String bodyText, String ctaLabel, String ctaUrl, int expiryHours) {
        Optional<EmailTheme> theme = tenant.getEmailTheme();
        return theme.isPresent()
                ? buildRich(tenant, theme.get(), headingPlain, headingAccent, bodyText, ctaLabel, ctaUrl, expiryHours)
                : buildSimple(tenant, headingPlain + " " + headingAccent, bodyText, ctaLabel, ctaUrl);
    }

    private static String buildRich(
            Tenant tenant,
            EmailTheme theme,
            String headingPlain,
            String headingAccent,
            String bodyText,
            String ctaLabel,
            String ctaUrl,
            int expiryHours) {
        String appName = escape(tenant.getAppName());
        String accent = safeColor(tenant.getPrimaryColor());
        String borderAccent = withAlpha(accent, 0.35);

        StringBuilder headerTagline = new StringBuilder();
        if (theme.headerTagline() != null) {
            String[] lines = theme.headerTagline().split("\n");
            for (int i = 0; i < lines.length; i++) {
                boolean last = i == lines.length - 1;
                headerTagline
                        .append("<div style=\"color:")
                        .append(last ? accent : "#c7d0d9")
                        .append(last ? ";font-weight:bold;" : ";")
                        .append("\">")
                        .append(escape(lines[i]))
                        .append("</div>");
            }
        }

        String logoImg = theme.logoUrl() == null
                ? ""
                : "<img src=\"" + escapeAttr(theme.logoUrl()) + "\" alt=\"" + appName + "\" height=\"40\" style=\"display:block;border:0;\" />";

        return "<!DOCTYPE html>"
                + "<html><body style=\"margin:0;padding:0;background-color:" + DARK_BG + ";font-family:Helvetica,Arial,sans-serif;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" bgcolor=\"" + DARK_BG + "\""
                + (theme.sideImageUrl() == null
                        ? ""
                        : " background=\"" + escapeAttr(theme.sideImageUrl()) + "\" style=\"background-image:url('"
                                + escapeAttr(theme.sideImageUrl()) + "');background-repeat:repeat-y;background-position:center;background-size:cover;\"")
                + ">"
                + "<tr><td align=\"center\" style=\"padding:40px 16px;\">"
                + "<table role=\"presentation\" width=\"640\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:640px;width:100%;background-color:"
                + PANEL_BG + ";border:1px solid " + borderAccent + ";border-radius:16px;overflow:hidden;\">"
                // Header
                + "<tr><td style=\"padding:32px 40px 0;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
                + "<td align=\"left\" valign=\"top\">" + logoImg
                + (theme.headerSubtitle() == null
                        ? ""
                        : "<div style=\"margin-top:8px;font-size:11px;letter-spacing:2px;color:" + TEXT_MUTED + ";\">"
                                + escape(theme.headerSubtitle()) + "</div>")
                + "</td>"
                + "<td align=\"right\" valign=\"top\" style=\"font-size:13px;line-height:1.5;\">" + headerTagline + "</td>"
                + "</tr></table>"
                + "</td></tr>"
                // Hero
                + "<tr><td style=\"padding:24px 40px 0;\">"
                + "<img src=\"" + escapeAttr(theme.heroImageUrl()) + "\" width=\"560\" style=\"width:100%;max-width:560px;display:block;"
                + "border-radius:12px;border:0;\" alt=\"\" />"
                + "</td></tr>"
                // Heading + greeting + body
                + "<tr><td style=\"padding:32px 40px 0;text-align:center;\">"
                + "<h1 style=\"margin:0 0 20px;font-size:28px;\"><span style=\"color:#ffffff;\">" + escape(headingPlain)
                + "</span> <span style=\"color:" + accent + ";\">" + escape(headingAccent) + "</span></h1>"
                + "<p style=\"margin:0 0 8px;font-size:16px;font-weight:bold;color:#ffffff;\">¡Hola!</p>"
                + "<p style=\"margin:0 auto 28px;max-width:440px;font-size:14px;line-height:1.7;color:" + TEXT_SOFT + ";\">"
                + withAppNameBold(bodyText, appName) + "</p>"
                + "</td></tr>"
                // CTA
                + "<tr><td align=\"center\" style=\"padding:0 40px;\">"
                + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\"><tr><td style=\"border-radius:8px;background-color:" + accent
                + ";\">"
                + "<a href=\"" + ctaUrl + "\" style=\"display:inline-block;padding:14px 32px;font-size:15px;font-weight:bold;color:#04140c;"
                + "text-decoration:none;\">" + escape(ctaLabel) + " →</a>"
                + "</td></tr></table>"
                + "</td></tr>"
                // Copy-paste link + expiry
                + "<tr><td style=\"padding:24px 40px 0;text-align:center;\">"
                + "<p style=\"margin:0 0 8px;font-size:12px;color:" + TEXT_MUTED + ";\">O copia y pega este enlace en tu navegador:</p>"
                + "<div style=\"background:" + DARK_BG + ";border:1px solid " + BORDER + ";border-radius:8px;padding:10px 16px;font-size:12px;"
                + "color:#c7d0d9;word-break:break-all;\">" + ctaUrl + "</div>"
                + "<p style=\"margin:16px 0 0;font-size:12px;color:" + TEXT_MUTED + ";\">Este enlace expirará en " + expiryHours + " horas.</p>"
                + "</td></tr>"
                // Didn't request this
                + "<tr><td style=\"padding:28px 40px 0;\">"
                + "<hr style=\"border:none;border-top:1px solid " + BORDER + ";margin:0 0 20px;\" />"
                + "<p style=\"margin:0 0 4px;font-size:13px;font-weight:bold;color:#ffffff;text-align:center;\">¿No fuiste tú?</p>"
                + "<p style=\"margin:0;font-size:12px;color:" + TEXT_MUTED + ";text-align:center;\">"
                + "Si no reconoces esta acción, puedes ignorar este correo de forma segura.</p>"
                + "</td></tr>"
                // Footer
                + "<tr><td style=\"padding:28px 40px 32px;\">"
                + "<hr style=\"border:none;border-top:1px solid " + BORDER + ";margin:0 0 20px;\" />"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>"
                + "<td align=\"left\" valign=\"middle\">" + logoImg
                + (theme.footerTagline() == null
                        ? ""
                        : "<div style=\"margin-top:4px;font-size:11px;color:#6b7684;\">" + escape(theme.footerTagline()) + "</div>")
                + "</td>"
                + "<td align=\"right\" valign=\"middle\" style=\"font-size:11px;color:" + TEXT_MUTED + ";\">" + socialLinks(theme) + "</td>"
                + "</tr></table>"
                + "<p style=\"margin:16px 0 0;font-size:11px;color:#4b5563;text-align:center;\">© " + Year.now() + " " + appName
                + ". Todos los derechos reservados.</p>"
                + "</td></tr>"
                + "</table>"
                + "</td></tr>"
                + "</table>"
                + "</body></html>";
    }

    // Text links, not icon images -- no icon assets hosted yet; upgrade
    // to real icons is a follow-up once those exist somewhere stable.
    private static String socialLinks(EmailTheme theme) {
        StringBuilder links = new StringBuilder();
        appendSocialLink(links, theme.discordUrl(), "Discord");
        appendSocialLink(links, theme.youtubeUrl(), "YouTube");
        appendSocialLink(links, theme.twitterUrl(), "X");
        appendSocialLink(links, theme.githubUrl(), "GitHub");
        return links.toString();
    }

    private static void appendSocialLink(StringBuilder out, String url, String label) {
        if (url == null) {
            return;
        }
        if (!out.isEmpty()) {
            out.append(" &middot; ");
        }
        out.append("<a href=\"").append(escapeAttr(url)).append("\" style=\"color:").append(TEXT_MUTED).append(";text-decoration:none;\">")
                .append(label)
                .append("</a>");
    }

    private static String buildSimple(Tenant tenant, String heading, String bodyText, String ctaLabel, String ctaUrl) {
        String appName = escape(tenant.getAppName());
        String accent = safeColor(tenant.getPrimaryColor());
        return "<!DOCTYPE html>"
                + "<html><body style=\"margin:0;padding:0;background-color:#f4f5f7;font-family:Helvetica,Arial,sans-serif;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background-color:#f4f5f7;padding:32px 16px;\">"
                + "<tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"480\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:480px;width:100%;background-color:#ffffff;border-radius:12px;overflow:hidden;\">"
                + "<tr><td style=\"background-color:" + accent + ";padding:24px 32px;\">"
                + "<span style=\"color:#ffffff;font-size:18px;font-weight:bold;\">" + appName + "</span>"
                + "</td></tr>"
                + "<tr><td style=\"padding:32px;\">"
                + "<h1 style=\"margin:0 0 16px;font-size:20px;color:#111827;\">" + escape(heading) + "</h1>"
                + "<p style=\"margin:0 0 24px;font-size:14px;line-height:1.6;color:#4b5563;\">" + escape(bodyText) + "</p>"
                + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\">"
                + "<tr><td style=\"border-radius:8px;background-color:" + accent + ";\">"
                + "<a href=\"" + ctaUrl + "\" style=\"display:inline-block;padding:12px 28px;font-size:14px;font-weight:bold;"
                + "color:#ffffff;text-decoration:none;border-radius:8px;\">" + escape(ctaLabel) + "</a>"
                + "</td></tr></table>"
                + "<p style=\"margin:24px 0 0;font-size:12px;line-height:1.5;color:#9ca3af;\">"
                + "Si el botón no funciona, copia y pega este link en tu navegador:<br />"
                + "<a href=\"" + ctaUrl + "\" style=\"color:#6b7280;word-break:break-all;\">" + ctaUrl + "</a>"
                + "</p>"
                + "</td></tr>"
                + "<tr><td style=\"padding:16px 32px;background-color:#f9fafb;\">"
                + "<p style=\"margin:0;font-size:11px;color:#9ca3af;\">" + appName + "</p>"
                + "</td></tr>"
                + "</table>"
                + "</td></tr>"
                + "</table>"
                + "</body></html>";
    }

    private static String safeColor(String primaryColor) {
        return primaryColor != null && HEX_COLOR.matcher(primaryColor).matches() ? primaryColor : DEFAULT_ACCENT;
    }

    /** Renders a hex color as {@code rgba(r,g,b,alpha)} -- used for a subtle tinted border, never a solid fill. */
    private static String withAlpha(String hex, double alpha) {
        String h = hex.length() == 4
                ? "" + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2) + hex.charAt(3) + hex.charAt(3)
                : hex.substring(1);
        int r = Integer.parseInt(h.substring(0, 2), 16);
        int g = Integer.parseInt(h.substring(2, 4), 16);
        int b = Integer.parseInt(h.substring(4, 6), 16);
        return "rgba(" + r + "," + g + "," + b + "," + alpha + ")";
    }

    /**
     * {@code bodyText} is escaped as plain text like everything else, but a
     * literal {@code {appName}} token in it is replaced afterward with the
     * (already-escaped) tenant name wrapped in {@code <strong>} — the one
     * bit of markup a caller can ask for inside otherwise-plain body copy,
     * matching the reference design's bolded tenant name mid-sentence.
     */
    private static String withAppNameBold(String bodyText, String escapedAppName) {
        return escape(bodyText).replace("{appName}", "<strong>" + escapedAppName + "</strong>");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Same escaping as {@link #escape}, named separately for the attribute-position call sites (URLs). */
    private static String escapeAttr(String value) {
        return escape(value);
    }
}
