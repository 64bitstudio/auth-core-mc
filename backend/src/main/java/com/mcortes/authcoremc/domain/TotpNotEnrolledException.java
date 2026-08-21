package com.mcortes.authcoremc.domain;

/** Thrown by {@link User#activateTwoFactorMethod} when TOTP is selected before a secret was ever enrolled. */
public class TotpNotEnrolledException extends RuntimeException {

    public TotpNotEnrolledException() {
        super("Cannot activate TOTP before enrolling a secret");
    }
}
