package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.TwoFactorMethod;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.repository.UserRepository;
import com.mcortes.authcoremc.security.RedisTokenStore;
import com.mcortes.authcoremc.service.DirectTokenService;
import com.mcortes.authcoremc.service.InvalidTokenException;
import com.mcortes.authcoremc.service.LoginCompletionService;
import com.mcortes.authcoremc.service.OtpService;
import com.mcortes.authcoremc.service.TokenPair;
import com.mcortes.authcoremc.service.TotpService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ticket 045, second half of the 2FA gate: completes a login {@link
 * LoginCompletionService} left pending — shared by both {@code
 * AuthController} (password) and {@code SocialExchangeController} (social),
 * since a pending token from either carries the same shape and this
 * verifies it the same way regardless of which flow issued it.
 *
 * <p>Consumes the {@code pendingToken} via {@code RedisTokenStore.consume}
 * (one-time use, same guarantee {@code SocialExchangeController}'s code
 * exchange already relies on), confirms the {@code X-Client-Id} header
 * matches the {@code clientId} packed into it at issuance, verifies the
 * code against whichever method the user actually has active ({@link
 * TotpService#verify} or {@link OtpService#verifyOtp} — reused exactly as
 * they are, including whatever rate-limiting each already has, not
 * duplicated here), and only then mints real tokens via {@link
 * DirectTokenService} directly — NOT via {@link LoginCompletionService}
 * again, which would just re-issue another pending token forever, since
 * the user's 2FA preference isn't cleared by a single successful
 * verification.
 *
 * <p>Every failure mode (unknown/expired/already-used pending token,
 * mismatched client, unknown user, wrong code) responds the same generic
 * {@code 400 invalid_token} — same "never reveal which part was wrong"
 * criterion {@code SocialExchangeController} already applies to its own
 * code exchange, except a wrong OTP code, which surfaces {@code
 * OtpService}'s own {@code 429 too_many_attempts} once its guess limit is
 * hit (reused, not reimplemented).
 */
@RestController
@RequestMapping("/api/v1/login")
public class TwoFactorLoginController {

    private final ClientContextResolver clientContextResolver;
    private final RedisTokenStore redisTokenStore;
    private final UserRepository userRepository;
    private final TotpService totpService;
    private final OtpService otpService;
    private final DirectTokenService directTokenService;

    public TwoFactorLoginController(
            ClientContextResolver clientContextResolver,
            RedisTokenStore redisTokenStore,
            UserRepository userRepository,
            TotpService totpService,
            OtpService otpService,
            DirectTokenService directTokenService) {
        this.clientContextResolver = clientContextResolver;
        this.redisTokenStore = redisTokenStore;
        this.userRepository = userRepository;
        this.totpService = totpService;
        this.otpService = otpService;
        this.directTokenService = directTokenService;
    }

    @PostMapping("/2fa-verify")
    public ResponseEntity<LoginResponse> verify(
            @RequestHeader("X-Client-Id") String clientId, @Valid @RequestBody TwoFactorVerifyRequest request) {
        String value = redisTokenStore
                .consume(LoginCompletionService.PENDING_2FA_PURPOSE, request.pendingToken())
                .orElseThrow(TwoFactorLoginController::invalidPendingToken);

        LoginCompletionService.PendingLogin pending;
        UUID userId;
        try {
            pending = LoginCompletionService.parsePendingLogin(value);
            userId = pending.userId();
        } catch (RuntimeException _) {
            // Malformed stored value — can't happen from a real flow, only from
            // a tampered/corrupted Redis entry. Same generic error as any other
            // failure mode here.
            throw invalidPendingToken();
        }

        if (!pending.clientId().equals(clientId)) {
            // Deliberately as generic as an unknown pending token — never reveal
            // that the token itself was fine but the client didn't match, same
            // criterion SocialExchangeController uses for a cross-tenant code.
            throw invalidPendingToken();
        }

        IdentityClient client = clientContextResolver.resolveClient(clientId);
        User user = userRepository.findById(userId).orElseThrow(TwoFactorLoginController::invalidPendingToken);

        verifyCode(user, request.code());

        TokenPair tokens = directTokenService.issueTokens(client, user);
        return ResponseEntity.ok(new LoginResponse(UserResponse.from(user), tokens));
    }

    private void verifyCode(User user, String code) {
        switch (user.getTwoFactorMethod()) {
            case TOTP -> totpService.verify(user, code);
            case OTP_EMAIL, OTP_SMS -> otpService.verifyOtp(user, code);
            case NONE ->
                // Defensive only: a pending token is never issued for TwoFactorMethod.NONE
                // (see LoginCompletionService#complete) — this would mean the user's 2FA
                // preference was turned off in the window between login and this call.
                throw invalidPendingToken();
        }
    }

    private static InvalidTokenException invalidPendingToken() {
        return new InvalidTokenException("The pending token is invalid, expired, or already used");
    }
}
