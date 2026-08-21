package com.mcortes.authcoremc.web;

import com.mcortes.authcoremc.domain.Tenant;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Server-rendered pages (ticket 009) for the flows the rest of {@code
 * web/**} already exposes as JSON. This controller only resolves the
 * tenant for theming and picks a view name — every form on these pages
 * submits via {@code fetch()} straight to the same {@code /api/v1/**}
 * endpoints any other API client uses (see {@code static/js/api.js}). No
 * business logic is duplicated here.
 *
 * <p><b>Why {@code client_id} is a query parameter here, not the {@code
 * X-Client-Id} header</b> the rest of the REST API uses: a plain page
 * navigation (typing a URL, clicking a link, an emailed link) can't attach
 * a custom header — only a page's own script can, and only for its own
 * {@code fetch()} calls afterward. So every page that needs tenant theming
 * carries {@code ?client_id=...} in its URL instead, consistent with how
 * ticket 007's {@code /oauth2/authorize} already uses a {@code client_id}
 * query parameter for the same reason.
 *
 * <p><b>Why {@code /ui/cuenta} doesn't ask for a userId</b>: the flows it
 * exposes (resend verification, change email, 2FA) all need one, but
 * asking a visitor to type their own UUID would be poor UX and pointless
 * to expose as a raw field. Instead, a successful {@code /ui/register} or
 * {@code /ui/login} stores it in {@code sessionStorage} client-side (see
 * {@code api.js}), and this page reads it back from there. This is a
 * client-side convenience, NOT a real server-enforced session — it
 * extends the same deliberate, documented temporary trust boundary
 * tickets 003/005 already accepted for these same endpoints (see
 * docs/ARQUITECTURA.md, ticket 009, for the full rationale and what a
 * real session-based integration with ticket 007's tokens would still
 * need to add).
 */
@Controller
@RequestMapping("/ui")
public class UiPagesController {

    /** Ticket 020: model attribute key the admin shell fragment reads to highlight the active nav link. */
    private static final String ACTIVE_ATTR = "active";

    private final ClientContextResolver clientContextResolver;

    public UiPagesController(ClientContextResolver clientContextResolver) {
        this.clientContextResolver = clientContextResolver;
    }

    @GetMapping("/register")
    public String register(@RequestParam("client_id") String clientId, Model model) {
        theme(clientId, model);
        return "register";
    }

    @GetMapping("/login")
    public String login(@RequestParam("client_id") String clientId, Model model) {
        theme(clientId, model);
        return "login";
    }

    @GetMapping("/cuenta")
    public String cuenta(@RequestParam("client_id") String clientId, Model model) {
        theme(clientId, model);
        return "cuenta";
    }

    @GetMapping("/password-reset/request")
    public String passwordResetRequest(@RequestParam("client_id") String clientId, Model model) {
        theme(clientId, model);
        return "password-reset-request";
    }

    @GetMapping("/password-reset/confirm")
    public String passwordResetConfirm() {
        return "password-reset-confirm";
    }

    @GetMapping("/verify-email/confirm")
    public String verifyEmailConfirm() {
        return "verify-email-confirm";
    }

    @GetMapping("/change-email/confirm")
    public String changeEmailConfirm() {
        return "change-email-confirm";
    }

    /**
     * Ticket 014: the real access control here is server-side (JWT role
     * gate on {@code /api/v1/admin/**}, ticket 012) — this route only
     * resolves theming, same as every other page in this controller. The
     * page's own script (see {@code admin-identity-providers.html})
     * redirects to {@code /ui/login} if there's no admin session token yet,
     * and surfaces a real 403 from the API if the logged-in user's role
     * isn't sufficient.
     */
    @GetMapping("/admin/identity-providers")
    public String adminIdentityProviders(@RequestParam("client_id") String clientId, Model model) {
        theme(clientId, model);
        model.addAttribute(ACTIVE_ATTR, "providers");
        return "admin-identity-providers";
    }

    /**
     * Ticket 016: same access-control note as {@link #adminIdentityProviders} —
     * this route only resolves theming. The page reads {@code tenant_id} off
     * the caller's own JWT to default the "which tenant" field (see {@code
     * AuthCoreUi.currentTenantId()}), editable so a platform_admin can query
     * a different tenant; the server enforces who is actually allowed to.
     */
    @GetMapping("/admin/metrics")
    public String adminMetrics(@RequestParam("client_id") String clientId, Model model) {
        theme(clientId, model);
        model.addAttribute(ACTIVE_ATTR, "metrics");
        return "admin-metrics";
    }

    /**
     * Ticket 019: list of every tenant — same access-control note as the
     * other admin pages (theming only here; the real gate is the 403 a
     * non-platform_admin gets from {@code GET /api/v1/admin/tenants}).
     */
    @GetMapping("/admin/tenants")
    public String adminTenants(@RequestParam("client_id") String clientId, Model model) {
        theme(clientId, model);
        model.addAttribute(ACTIVE_ATTR, "tenants");
        return "admin-tenants";
    }

    private void theme(String clientId, Model model) {
        Tenant tenant = clientContextResolver.resolveTenant(clientId);
        model.addAttribute("clientId", clientId);
        model.addAttribute("appName", tenant.getAppName());
        model.addAttribute("primaryColor", tenant.getPrimaryColor());
    }
}
