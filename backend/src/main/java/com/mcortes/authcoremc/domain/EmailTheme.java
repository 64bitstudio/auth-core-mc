package com.mcortes.authcoremc.domain;

/**
 * Ticket 058: optional rich branding for a tenant's transactional emails
 * (hero art, side background, header/footer copy, social links) — layered
 * on top of the simple {@code app_name}/{@code primary_color} theme from
 * ticket 057, never replacing it. All fields are plain image URLs or
 * short text, filled into a FIXED layout {@code BrandedEmailTemplate}
 * controls — never arbitrary tenant-supplied HTML/markup, so a tenant
 * can't inject anything beyond an image or a line of text into its own
 * email.
 *
 * @param headerTagline multi-line (separated by {@code \n}); the last
 *     line renders in the tenant's accent color, matching the "Tu mundo. /
 *     Tus criaturas. / Sin límites." example.
 */
public record EmailTheme(
        String logoUrl,
        String heroImageUrl,
        String sideImageUrl,
        String headerSubtitle,
        String headerTagline,
        String footerTagline,
        String discordUrl,
        String youtubeUrl,
        String twitterUrl,
        String githubUrl) {}
