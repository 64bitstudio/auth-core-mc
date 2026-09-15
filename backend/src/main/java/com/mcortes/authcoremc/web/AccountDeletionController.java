package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.service.AccountDeletionService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ticket 064 ("Mi Perfil", galgoth-studio) -- elimina la cuenta del
 * usuario autenticado. Mismo mecanismo de auth que el resto de
 * {@code /api/v1/account/**}: Bearer real, {@code userId} del {@code sub}
 * del JWT. {@code 204 No Content} en éxito; {@code 400} si
 * {@code confirmIdentifier} no coincide; {@code 500} si la purga síncrona
 * de galgoth-studio falla (ver {@code AccountDeletionService}).
 */
@RestController
@RequestMapping("/api/v1/account")
public class AccountDeletionController {

    private final AccountDeletionService accountDeletionService;

    public AccountDeletionController(AccountDeletionService accountDeletionService) {
        this.accountDeletionService = accountDeletionService;
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeleteAccountRequest request) {
        accountDeletionService.deleteAccount(UUID.fromString(jwt.getSubject()), request.confirmIdentifier());
        return ResponseEntity.noContent().build();
    }
}
