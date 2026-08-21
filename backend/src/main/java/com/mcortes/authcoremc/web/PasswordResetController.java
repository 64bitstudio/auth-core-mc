package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.service.PasswordResetService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /request} always returns 202 no matter what happened internally —
 * see {@link PasswordResetService#requestReset} for why revealing anything
 * else here would leak whether an identifier belongs to a real account.
 */
@RestController
@RequestMapping("/api/v1/password-reset")
public class PasswordResetController {

    private final ClientContextResolver clientContextResolver;
    private final PasswordResetService passwordResetService;

    public PasswordResetController(
            ClientContextResolver clientContextResolver, PasswordResetService passwordResetService) {
        this.clientContextResolver = clientContextResolver;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/request")
    public ResponseEntity<Void> request(
            @RequestHeader("X-Client-Id") String clientId, @Valid @RequestBody RequestPasswordResetRequest request) {
        Tenant tenant = clientContextResolver.resolveTenant(clientId);
        passwordResetService.requestReset(tenant, request.identifier());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@Valid @RequestBody ConfirmPasswordResetRequest request) {
        passwordResetService.confirmReset(request.token(), request.newPassword());
        return ResponseEntity.ok().build();
    }
}
