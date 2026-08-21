package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.service.RegistrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RegistrationController {

    private final ClientContextResolver clientContextResolver;
    private final RegistrationService registrationService;

    public RegistrationController(ClientContextResolver clientContextResolver, RegistrationService registrationService) {
        this.clientContextResolver = clientContextResolver;
        this.registrationService = registrationService;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(
            @RequestHeader("X-Client-Id") String clientId, @Valid @RequestBody RegisterRequest request) {
        Tenant tenant = clientContextResolver.resolveTenant(clientId);
        User user = registrationService.register(
                tenant, request.email(), request.phone(), request.nombre(), request.apellidos(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }
}
