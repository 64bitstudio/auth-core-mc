package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.Tenant;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.service.OtpService;
import com.mcortes.authcoremc.service.TotpService;
import com.mcortes.authcoremc.service.TwoFactorPreferenceService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/2fa")
public class TwoFactorController {

    private final ClientContextResolver clientContextResolver;
    private final TenantScopedUserResolver userResolver;
    private final OtpService otpService;
    private final TotpService totpService;
    private final TwoFactorPreferenceService preferenceService;

    public TwoFactorController(
            ClientContextResolver clientContextResolver,
            TenantScopedUserResolver userResolver,
            OtpService otpService,
            TotpService totpService,
            TwoFactorPreferenceService preferenceService) {
        this.clientContextResolver = clientContextResolver;
        this.userResolver = userResolver;
        this.otpService = otpService;
        this.totpService = totpService;
        this.preferenceService = preferenceService;
    }

    @PostMapping("/otp/request")
    public ResponseEntity<Void> requestOtp(
            @RequestHeader("X-Client-Id") String clientId, @Valid @RequestBody UserIdRequest request) {
        User user = resolveUser(clientId, request.userId());
        otpService.requestOtp(user);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<Void> verifyOtp(
            @RequestHeader("X-Client-Id") String clientId, @Valid @RequestBody VerifyCodeRequest request) {
        User user = resolveUser(clientId, request.userId());
        otpService.verifyOtp(user, request.code());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/totp/enroll")
    public ResponseEntity<TotpEnrollResponse> enrollTotp(
            @RequestHeader("X-Client-Id") String clientId, @Valid @RequestBody UserIdRequest request) {
        User user = resolveUser(clientId, request.userId());
        String secret = totpService.enroll(user);
        return ResponseEntity.ok(new TotpEnrollResponse(secret));
    }

    @PostMapping("/totp/verify")
    public ResponseEntity<Void> verifyTotp(
            @RequestHeader("X-Client-Id") String clientId, @Valid @RequestBody VerifyCodeRequest request) {
        User user = resolveUser(clientId, request.userId());
        totpService.verify(user, request.code());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/method")
    public ResponseEntity<Void> activateMethod(
            @RequestHeader("X-Client-Id") String clientId, @Valid @RequestBody ActivateTwoFactorRequest request) {
        User user = resolveUser(clientId, request.userId());
        preferenceService.activate(user, request.method());
        return ResponseEntity.ok().build();
    }

    private User resolveUser(String clientId, java.util.UUID userId) {
        Tenant tenant = clientContextResolver.resolveTenant(clientId);
        return userResolver.resolve(tenant, userId);
    }
}
