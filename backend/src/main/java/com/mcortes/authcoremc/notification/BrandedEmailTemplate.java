package com.mcortes.authcoremc.notification;

import com.mcortes.authcoremc.domain.Tenant;
import java.util.regex.Pattern;

/**
 * Builds the HTML body for verification/reset/change-email emails, themed
 * per tenant with {@code tenant.app_name}/{@code tenant.primary_color} --
 * data that already existed (tenant branding, used today for the hosted
 * `/ui/**` pages) and is now reused here instead of adding a new column
 * just for email branding.
 *
 * <p>Table-based layout with inline styles on purpose: `<style>` blocks
 * are stripped or unreliable in several real email clients (Outlook
 * desktop chief among them) -- inline is the only style application that
 * reliably survives across clients.
 */
public final class BrandedEmailTemplate {

    // Fallback for a tenant whose primary_color isn't a well-formed hex
    // value -- admin-set data, not attacker-controlled, but this embeds
    // straight into an HTML attribute, so a malformed/hostile value
    // shouldn't reach the markup unchecked.
    private static final String DEFAULT_ACCENT = "#0057FF";
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{3,8}$");

    private BrandedEmailTemplate() {}

    /**
     * @param heading short, e.g. "Verify your account".
     * @param bodyText one or two sentences of context above the button.
     * @param ctaLabel button text, e.g. "Verify account".
     * @param ctaUrl the real link (already built per-client by
     *     {@link VerificationLinkFactory}).
     */
    public static String build(Tenant tenant, String heading, String bodyText, String ctaLabel, String ctaUrl) {
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

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
