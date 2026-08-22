package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.TwoFactorMethod;

/**
 * Body of the {@code 202} response {@code /api/v1/login} and {@code
 * /api/v1/oauth2/social-exchange} return (ticket 045) when the resolved
 * user has 2FA active — no tokens yet. {@code twoFactorRequired} is always
 * {@code true} here; it exists so the body stays self-describing even if a
 * client only inspects the JSON and not the status code. The client
 * completes the login via {@code POST /api/v1/login/2fa-verify} with this
 * exact {@code pendingToken} plus the {@code code} for {@code method}.
 */
public record TwoFactorRequiredResponse(boolean twoFactorRequired, String pendingToken, TwoFactorMethod method) {

    public TwoFactorRequiredResponse(String pendingToken, TwoFactorMethod method) {
        this(true, pendingToken, method);
    }
}
