package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.service.EmailVerificationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        Tenant tenant = clientContextResolver.resolveTenant(clientId);
        User user = userResolver.resolve(tenant, request.userId());
        verificationService.requestVerification(user);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@Valid @RequestBody ConfirmTokenRequest request) {
        verificationService.confirmVerification(request.token());
        return ResponseEntity.ok().build();
    }
}
