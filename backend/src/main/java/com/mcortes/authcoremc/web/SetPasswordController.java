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
 * {@code .anyRequest().authenticated()} rule for free) -- the same
 * treatment {@code /api/v1/2fa} and {@code /api/v1/change-email} ALSO
 * get since the 2026-09-15 security fix (see {@link EmailChangeController}'s
 * Javadoc): those two used to trust a client-supplied {@code userId}
 * instead, reasoned as "annoying, not exploitable" because completing
 * those flows still required possessing a token sent to an inbox/phone
 * the caller controls -- reasoning that held for password-reset but broke
 * down for change-email (the token lands in the ATTACKER's chosen inbox)
 * and totally didn't apply to 2FA enrollment (no inbox involved at all).
 * Setting a password never had that excuse to begin with: it takes effect
 * immediately and would let anyone who
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
