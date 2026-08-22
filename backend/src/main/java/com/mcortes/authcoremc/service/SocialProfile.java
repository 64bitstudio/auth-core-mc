package com.mcortes.authcoremc.service;

/**
 * The subset of a Google/Facebook profile {@link SocialLoginUserResolver}
 * needs, already normalized by the caller ({@code SocialLoginSuccessHandler},
 * ticket 037) from an {@code OidcUser} (Google) or plain {@code OAuth2User}
 * (Facebook) into one shape.
 *
 * @param email the address the provider reports — for Facebook, only ever
 *     present when the user granted the email permission (see HU-1's
 *     Facebook criterion); the caller never fabricates one
 * @param emailVerified {@code true} only when the provider itself vouches
 *     for the address: Google's {@code email_verified} claim, or — for
 *     Facebook, which has no equivalent claim — the mere presence of
 *     {@code email} (Facebook only ever returns it once verified on its own
 *     side, docs/definiciones/login-social-real.md, Diseño técnico, decisión
 *     3)
 * @param providerUserId the provider's stable subject id (Google's
 *     {@code sub}, Facebook's {@code id}) — never the email
 * @param givenName first name, if the provider returned one
 * @param familyName last name, if the provider returned one
 */
public record SocialProfile(
        String email, boolean emailVerified, String providerUserId, String givenName, String familyName) {}
