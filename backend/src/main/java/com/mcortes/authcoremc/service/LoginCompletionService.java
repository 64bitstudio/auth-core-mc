package com.mcortes.authcoremc.service;

import com.mcortes.authcoremc.domain.IdentityClient;
import com.mcortes.authcoremc.domain.TwoFactorMethod;
import com.mcortes.authcoremc.domain.User;
import com.mcortes.authcoremc.security.RedisTokenStore;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Ticket 045: the single decision point both {@code AuthController}
 * ({@code POST /api/v1/login}) and {@code SocialExchangeController}
 * ({@code POST /api/v1/oauth2/social-exchange}) call once they've each
 * independently proven who the user is (password / social identity) —
 * exactly the point where both used to call {@link DirectTokenService}
 * directly. One implementation, not two parallel gates.
 *
 * <p>If the user has no 2FA method active ({@link TwoFactorMethod#NONE}),
 * behavior is byte-for-byte unchanged from before this ticket: tokens are
 * minted right away via {@link DirectTokenService}.
 *
 * <p>Otherwise, no tokens are minted yet. A short-lived (5 min), single-use
 * "pending" token is issued via {@link RedisTokenStore} under {@link
 * #PENDING_2FA_PURPOSE} — the exact same one-time-token mechanism {@code
 * SocialLoginSuccessHandler} already uses for its own exchange code, not a
 * new store. Its value packs {@code clientId + SEPARATOR + userId} (same
 * "pack both, split on read" style {@code EmailChangeService} already
 * uses) so {@code TwoFactorLoginController} can later recover both without
 * trusting anything the client sends except the pending token itself, and
 * can reject a mismatched {@code X-Client-Id} the same generic way {@code
 * SocialExchangeController} already rejects a cross-tenant code — see
 * {@link #parsePendingLogin}.
 *
 * <p><b>OTP send-on-gate decision:</b> for an OTP method (email/SMS), the
 * code is sent right here, via {@link OtpService#requestOtp} — because the
 * pending response deliberately does NOT carry the {@code userId} that
 * {@code POST /api/v1/2fa/otp/request} would need to send one later, and no
 * UI exists yet to drive a separate "send me a code" step (ticket 045's own
 * explicitly-flagged UI gap). Without this, an OTP-gated login would be a
 * dead end: a {@code pendingToken} with no code ever having been sent. A
 * resend-cooldown collision ({@link TooManyAttemptsException}) is swallowed
 * here, not surfaced as a login failure — it only means a code sent
 * moments ago (e.g. a rapid retry) is still valid, which isn't a problem
 * with this attempt. TOTP needs no such step: the code already lives in
 * the user's authenticator app.
 */
@Service
public class LoginCompletionService {

    /**
     * Public (not package-private) — {@code TwoFactorLoginController}, in
     * the {@code web} package, needs it to consume the same pending token
     * via {@code RedisTokenStore.consume(...)}. Same reasoning as {@code
     * SocialLoginSuccessHandler.EXCHANGE_PURPOSE}.
     */
    public static final String PENDING_2FA_PURPOSE = "login-2fa-pending";

    private static final String SEPARATOR = "::";
    private static final Duration PENDING_2FA_TTL = Duration.ofMinutes(5);

    private final DirectTokenService directTokenService;
    private final RedisTokenStore redisTokenStore;
    private final OtpService otpService;

    public LoginCompletionService(
            DirectTokenService directTokenService, RedisTokenStore redisTokenStore, OtpService otpService) {
        this.directTokenService = directTokenService;
        this.redisTokenStore = redisTokenStore;
        this.otpService = otpService;
    }

    public LoginCompletionResult complete(IdentityClient client, User user) {
        TwoFactorMethod method = user.getTwoFactorMethod();
        if (method == TwoFactorMethod.NONE) {
            return LoginCompletionResult.completed(user, directTokenService.issueTokens(client, user));
        }

        if (method == TwoFactorMethod.OTP_EMAIL || method == TwoFactorMethod.OTP_SMS) {
            try {
                otpService.requestOtp(user);
            } catch (TooManyAttemptsException _) {
                // See class Javadoc — a very recent code is still valid, not a login failure.
            }
        }

        String value = client.getClientId() + SEPARATOR + user.getId();
        String pendingToken = redisTokenStore.issue(PENDING_2FA_PURPOSE, value, PENDING_2FA_TTL);
        return LoginCompletionResult.twoFactorRequired(pendingToken, method);
    }

    /**
     * Parses a pending token's stored value back into the {@code clientId}
     * and {@code userId} it packed at {@link #complete} time. Thrown
     * {@link IllegalArgumentException}/{@link ArrayIndexOutOfBoundsException}
     * on a malformed value are deliberately left to the caller ({@code
     * TwoFactorLoginController}) to translate into the same generic
     * "invalid token" response as every other failure mode there — a
     * malformed value can only mean the token store itself was tampered
     * with, not a real client mistake.
     */
    public static PendingLogin parsePendingLogin(String value) {
        String[] parts = value.split(SEPARATOR, 2);
        return new PendingLogin(parts[0], UUID.fromString(parts[1]));
    }

    /** {@code clientId} + {@code userId} recovered from a pending token's value — see {@link #parsePendingLogin}. */
    public record PendingLogin(String clientId, UUID userId) {}
}
