package com.mcortes.authcoremc.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Sends SMS via the Twilio API (docs/ARQUITECTURA.md decision 7). Same
 * fail-loudly-without-credentials contract as {@link ResendEmailSender} —
 * see its Javadoc for why.
 */
@Component
public class TwilioSmsSender implements SmsSender {

    private final RestClient restClient;
    private final String fromNumber;
    private final boolean configured;

    public TwilioSmsSender(
            RestClient.Builder restClientBuilder,
            @Value("${twilio.account-sid:}") String accountSid,
            @Value("${twilio.auth-token:}") String authToken,
            @Value("${twilio.from-number:}") String fromNumber) {
        this.configured = !accountSid.isBlank() && !authToken.isBlank();
        this.fromNumber = fromNumber;
        this.restClient = restClientBuilder
                .baseUrl("https://api.twilio.com/2010-04-01/Accounts/" + accountSid)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBasicAuth(accountSid, authToken);
                    return execution.execute(request, body);
                })
                .build();
    }

    @Override
    public void send(String to, String body) {
        if (!configured) {
            throw new IllegalStateException(
                    "TWILIO_ACCOUNT_SID/TWILIO_AUTH_TOKEN are not configured — cannot send SMS. Set them in "
                            + "dev-infra/.env or this project's own .env before triggering any SMS-sending flow.");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", to);
        form.add("From", fromNumber);
        form.add("Body", body);

        restClient
                .post()
                .uri("/Messages.json")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toBodilessEntity();
    }
}
