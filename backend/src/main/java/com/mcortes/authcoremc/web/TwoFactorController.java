package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.service.OtpService;
import com.mcortes.authcoremc.service.TotpService;
import com.mcortes.authcoremc.service.TwoFactorPreferenceService;
import com.mcortes.authcoremc.service.UserNotFoundException;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hallazgo real de seguridad (2026-09-15) -- ver docstring completo de
 * {@link EmailChangeController}. Este controller era el más grave de los
 * 3: a diferencia de {@code change-email}/{@code verify-email} (que al
 * menos mandan un correo/código a un canal que la víctima ya controla),
 * {@code totp/enroll} devuelve el secreto DIRECTO en la respuesta -- un
 * atacante podía enrolar su propio secreto TOTP en la cuenta de otro
 * usuario (sin mandar nada a ningún lado que la víctima pudiera ver) y
 * activar 2FA con {@code /method}, dejándola bloqueada permanentemente
 * sin su consentimiento. Corregido igual que el resto de {@code
 * /account/**}: el usuario sale exclusivamente del JWT verificado, nunca
 * de un {@code userId} en el body -- por eso ya no necesita
 * {@code ClientContextResolver}/{@code TenantScopedUserResolver} en
 * absoluto (ningún servicio de acá necesita el cliente, solo el usuario).
 */
@RestController
@RequestMapping("/api/v1/2fa")
public class TwoFactorController {

    private final UserRepository userRepository;
    private final OtpService otpService;
    private final TotpService totpService;
    private final TwoFactorPreferenceService preferenceService;

    public TwoFactorController(
            UserRepository userRepository,
            OtpService otpService,
            TotpService totpService,
            TwoFactorPreferenceService preferenceService) {
        this.userRepository = userRepository;
        this.otpService = otpService;
        this.totpService = totpService;
        this.preferenceService = preferenceService;
    }

    @PostMapping("/otp/request")
    public ResponseEntity<Void> requestOtp(@AuthenticationPrincipal Jwt jwt) {
        otpService.requestOtp(resolveUser(jwt));
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/otp/verify")
    public ResponseEntity<Void> verifyOtp(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody VerifyCodeRequest request) {
        otpService.verifyOtp(resolveUser(jwt), request.code());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/totp/enroll")
    public ResponseEntity<TotpEnrollResponse> enrollTotp(@AuthenticationPrincipal Jwt jwt) {
        String secret = totpService.enroll(resolveUser(jwt));
        return ResponseEntity.ok(new TotpEnrollResponse(secret));
    }

    @PostMapping("/totp/verify")
    public ResponseEntity<Void> verifyTotp(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody VerifyCodeRequest request) {
        totpService.verify(resolveUser(jwt), request.code());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/method")
    public ResponseEntity<Void> activateMethod(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ActivateTwoFactorRequest request) {
        preferenceService.activate(resolveUser(jwt), request.method());
        return ResponseEntity.ok().build();
    }

    private User resolveUser(Jwt jwt) {
        return userRepository.findById(UUID.fromString(jwt.getSubject())).orElseThrow(UserNotFoundException::new);
    }
}
