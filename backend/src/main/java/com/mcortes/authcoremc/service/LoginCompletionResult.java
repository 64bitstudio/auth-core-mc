package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.TwoFactorMethod;
import com.mcortes.authcoremc.domain.User;

/**
 * Result of {@link LoginCompletionService#complete}: either tokens were
 * minted right away ({@link #twoFactorRequired()} {@code == false} —
 * {@code user}/{@code tokens} set, {@code pendingToken}/{@code method}
 * {@code null}), or a second factor is still needed ({@code
 * twoFactorRequired() == true} — {@code pendingToken}/{@code method} set,
 * {@code user}/{@code tokens} {@code null}). Plain nullable-fields record
 * (not a sealed hierarchy) to match this codebase's existing DTO style —
 * see {@link #completed} / {@link #twoFactorRequired(String, TwoFactorMethod)}
 * for the only two ways to build one.
 */
public record LoginCompletionResult(User user, TokenPair tokens, String pendingToken, TwoFactorMethod method) {

    public static LoginCompletionResult completed(User user, TokenPair tokens) {
        return new LoginCompletionResult(user, tokens, null, null);
    }

    public static LoginCompletionResult twoFactorRequired(String pendingToken, TwoFactorMethod method) {
        return new LoginCompletionResult(null, null, pendingToken, method);
    }

    public boolean twoFactorRequired() {
        return pendingToken != null;
    }
}
