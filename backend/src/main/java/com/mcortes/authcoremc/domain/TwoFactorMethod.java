package com.mcortes.authcoremc.domain;

/** A user's chosen second factor, if any (ticket 005). */
public enum TwoFactorMethod {
    NONE,
    OTP_EMAIL,
    OTP_SMS,
    TOTP
}
