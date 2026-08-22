package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.service.SetPasswordService;
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
 * HU-5 (ticket 041, docs/definiciones/login-social-real.md): lets the
 * caller set a password on their OWN account, if it doesn't have one yet.
 *
 * <p>Deliberately authenticated with the real Bearer access token (same
 * {@code JwtDecoder}/resource-server chain {@code AdminTenantController}
 * already uses — this path is simply never added to {@code SecurityConfig}'s
 * {@code permitAll} list, so it falls under the existing
 * {@code .anyRequest().authenticated()} rule for free) rather than the
 * client-supplied-{@code userId} trust boundary {@code /api/v1/2fa} and
 * {@code /api/v1/change-email} still use (see {@code
 * TenantScopedUserResolver}'s Javadoc). That boundary is deliberately
 * accepted there because completing those flows still requires possessing
 * a token sent to an inbox/phone the caller controls — a guessed userId is
 * "annoying, not exploitable". Setting a password has no such second
 * factor: it takes effect immediately and would let anyone who
 * knows/guesses another social-only user's id log in as them right away.
 * The user id therefore comes from the verified JWT's {@code sub} claim
 * (see {@code DirectTokenService#generateAccessToken}), never from the
 * request body.
 */
@RestController
@RequestMapping("/api/v1/account")
public class SetPasswordController {

    private final SetPasswordService setPasswordService;

    public SetPasswordController(SetPasswordService setPasswordService) {
        this.setPasswordService = setPasswordService;
    }

    @PostMapping("/password")
    public ResponseEntity<UserResponse> setPassword(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SetPasswordRequest request) {
        User user = setPasswordService.setPassword(UUID.fromString(jwt.getSubject()), request.newPassword());
        return ResponseEntity.ok(UserResponse.from(user));
    }
}
