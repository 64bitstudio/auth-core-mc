package com.mcortes.authcoremc.service;

/** Ticket 064: a deactivated (deleted) user is rejected at token-issuance time — see {@code DirectTokenService#doIssueTokens}. Same criterion as {@code TenantDeactivatedException}. */
public class UserDeactivatedException extends RuntimeException {

    public UserDeactivatedException() {
        super("This account has been deleted");
    }
}
