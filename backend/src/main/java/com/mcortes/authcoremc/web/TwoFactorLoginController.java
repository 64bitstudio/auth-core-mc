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
 *
 * <p><b>Ticket 046 — {@code /2fa-resend}:</b> the UI gap ticket 045
 * explicitly flagged. Uses {@code RedisTokenStore.peek} rather than {@code
 * consume} — resending a code must not burn the one-time {@code
 * pendingToken} the user still needs for the real {@code /2fa-verify}
 * call. Unlike {@code verify}, a resend cooldown collision ({@link
 * com.mcortes.authcoremc.service.TooManyAttemptsException}) is NOT
 * swallowed here (unlike {@code LoginCompletionService}'s automatic
 * first send) — the user explicitly asked for this one, so {@code
 * OtpService}'s real {@code 429 too_many_attempts} is exactly the
 * feedback they need ("a code was already sent, wait").
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
        LoginCompletionService.PendingLogin pending = parsePendingLoginOrThrow(value, clientId);

        IdentityClient client = clientContextResolver.resolveClient(clientId);
        User user =
                userRepository.findById(pending.userId()).orElseThrow(TwoFactorLoginController::invalidPendingToken);

        verifyCode(user, request.code());

        TokenPair tokens = directTokenService.issueTokens(client, user);
        return ResponseEntity.ok(new LoginResponse(UserResponse.from(user), tokens));
    }

    @PostMapping("/2fa-resend")
    public ResponseEntity<Void> resend(
            @RequestHeader("X-Client-Id") String clientId, @Valid @RequestBody TwoFactorResendRequest request) {
        // peek, not consume — a resend must not burn the pendingToken the
        // user still needs for the real /2fa-verify call (see class Javadoc).
        String value = redisTokenStore
                .peek(LoginCompletionService.PENDING_2FA_PURPOSE, request.pendingToken())
                .orElseThrow(TwoFactorLoginController::invalidPendingToken);
        LoginCompletionService.PendingLogin pending = parsePendingLoginOrThrow(value, clientId);

        User user =
                userRepository.findById(pending.userId()).orElseThrow(TwoFactorLoginController::invalidPendingToken);

        // Only OTP methods have anything to resend — TOTP's code already lives
        // in the user's authenticator app. Defensive no-op otherwise, same
        // criterion as verifyCode()'s NONE case: can't happen from a real
        // flow (a pendingToken is never issued for TwoFactorMethod.NONE), not
        // worth a special error for.
        if (user.getTwoFactorMethod() == TwoFactorMethod.OTP_EMAIL || user.getTwoFactorMethod() == TwoFactorMethod.OTP_SMS) {
            otpService.requestOtp(user);
        }
        return ResponseEntity.accepted().build();
    }

    /**
     * Shared by {@code verify}/{@code resend}: parses a pending token's
     * stored value and confirms the packed {@code clientId} matches the
     * caller's {@code X-Client-Id} — both failure modes (malformed value,
     * mismatched client) collapse into the same generic {@code
     * invalid_token}, never revealing which part was wrong (same criterion
     * {@code SocialExchangeController} applies to its own code exchange).
     */
    private static LoginCompletionService.PendingLogin parsePendingLoginOrThrow(String value, String clientId) {
        LoginCompletionService.PendingLogin pending;
        try {
            pending = LoginCompletionService.parsePendingLogin(value);
        } catch (RuntimeException _) {
            // Malformed stored value — can't happen from a real flow, only from
            // a tampered/corrupted Redis entry.
            throw invalidPendingToken();
        }
        if (!pending.clientId().equals(clientId)) {
            throw invalidPendingToken();
        }
        return pending;
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
