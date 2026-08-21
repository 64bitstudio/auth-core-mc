package com.mcortes.authcoremc.notification;

/**
 * Abstraction over the transactional email provider. Kept separate from any
 * particular vendor (Resend today) so the business logic in `service` never
 * depends on Resend directly — swapping providers later means writing a new
 * implementation of this interface, not touching EmailVerificationService
 * or EmailChangeService (see docs/ARQUITECTURA.md).
 */
public interface EmailSender {

    void send(String to, String subject, String htmlBody);
}
