package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.service.EmailVerificationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hallazgo real de seguridad (2026-09-15, ticket 071) revisó los 3
 * endpoints que confiaban un {@code userId} del body sin autenticación
 * real ({@link EmailChangeController}/{@link TwoFactorController}/este) y
 * migró los otros 2 a JWT real -- este se queda deliberadamente como
 * estaba, {@code userId} incluido: a diferencia de {@code change-email}
 * (que manda su correo a una dirección que el ATACANTE elige) y de {@code
 * 2fa/totp/enroll} (que no manda nada a ningún lado), este endpoint solo
 * reenvía a la dirección YA registrada de la cuenta, con un cooldown de
 * 60s -- adivinar un {@code userId} ajeno aquí sigue siendo, en la
 * práctica real, "molesto (spam acotado), no explotable". Migrarlo de
 * todas formas habría roto el único caller real que lo usa sin sesión
 * (`RegisterView.vue` de galgoth-studio dispara el primer correo de
 * verificación justo después de registrarse, momento en el que
 * {@code POST /api/v1/register} deliberadamente NO entrega tokens --
 * `PROP-GS-AUTH-01` Fig. 02) a cambio de cerrar un hueco que, en este
 * caso puntual, nunca fue de verdad explotable.
 */
@RestController
@RequestMapping("/api/v1/verify-email")
public class EmailVerificationController {

    private final ClientContextResolver clientContextResolver;
    private final TenantScopedUserResolver userResolver;
    private final EmailVerificationService verificationService;

    public EmailVerificationController(
            ClientContextResolver clientContextResolver,
            TenantScopedUserResolver userResolver,
            EmailVerificationService verificationService) {
        this.clientContextResolver = clientContextResolver;
        this.userResolver = userResolver;
        this.verificationService = verificationService;
    }

    @PostMapping("/request")
    public ResponseEntity<Void> request(
            @RequestHeader("X-Client-Id") String clientId, @Valid @RequestBody RequestVerificationRequest request) {
        IdentityClient client = clientContextResolver.resolveClient(clientId);
        User user = userResolver.resolve(client.getTenant(), request.userId());
        verificationService.requestVerification(user, client);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@Valid @RequestBody ConfirmTokenRequest request) {
        verificationService.confirmVerification(request.token());
        return ResponseEntity.ok().build();
    }
}
