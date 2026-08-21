package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.service.EmailChangeService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/change-email")
public class EmailChangeController {

    private final ClientContextResolver clientContextResolver;
    private final TenantScopedUserResolver userResolver;
    private final EmailChangeService emailChangeService;

    public EmailChangeController(
            ClientContextResolver clientContextResolver,
            TenantScopedUserResolver userResolver,
            EmailChangeService emailChangeService) {
        this.clientContextResolver = clientContextResolver;
        this.userResolver = userResolver;
        this.emailChangeService = emailChangeService;
    }

    @PostMapping("/request")
    public ResponseEntity<Void> request(
            @RequestHeader("X-Client-Id") String clientId, @Valid @RequestBody RequestEmailChangeRequest request) {
        Tenant tenant = clientContextResolver.resolveTenant(clientId);
        User user = userResolver.resolve(tenant, request.userId());
        emailChangeService.requestChange(user, request.newEmail());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@Valid @RequestBody ConfirmTokenRequest request) {
        emailChangeService.confirmChange(request.token());
        return ResponseEntity.ok().build();
    }
}
