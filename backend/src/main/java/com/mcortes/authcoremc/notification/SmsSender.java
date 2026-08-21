package com.mcortes.authcoremc.notification;

/** Abstraction over the SMS provider — mirrors {@link EmailSender}'s reasoning. */
public interface SmsSender {

    void send(String to, String body);
}
