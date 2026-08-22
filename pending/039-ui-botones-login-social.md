# 039 — UI: botones de login social + página de canje + página de error

## Objetivo
Nace de `docs/definiciones/login-social-real.md` (HU-1, HU-3, HU-4). Primera vez que un usuario final ve login social de verdad: el botón, la página intermedia que canjea el código, y la página de error para el camino de fallo.

**Depende de:** tickets 036 (rutas `/oauth2/authorization/**` ya permitAll y resolviendo tenant) y 038 (endpoint de canje ya disponible). Puede desarrollarse en paralelo con 037/038 mockeando el endpoint, pero no se verifica en vivo hasta que ambos estén mergeados.

## Criterios de aceptación (TDD)
- `login.html` **y `register.html`**: botón "Iniciar sesión con Google" y "Iniciar sesión con Facebook", visibles **solo si el tenant tiene ese proveedor `enabled`** (nueva info que el modelo de Thymeleaf de esas páginas necesita recibir — vía `UiPagesController`, consultando `TenantIdentityProviderService`). Cada botón enlaza a `/oauth2/authorization/{identityClientId}::{provider}` con el `identityClientId` del tenant actual (resuelto igual que ya se resuelve el resto del theming de esas páginas).
- Estilo de los botones consistente con el sistema visual ya existente (`app.css`, íconos de marca `logo-google`/`logo-facebook` del ticket 024, ya usados en `admin-identity-providers.html` — reutilizar tal cual, no rediseñar los logos).
- Plantilla nueva `social-callback.html` (`/ui/social-callback?client_id=...&code=...`): al cargar, hace `POST /api/v1/oauth2/social-exchange` con el código, y en éxito llama a `AuthCoreUi.saveSession(...)` y redirige a `/ui/cuenta` — mismo patrón que ya usan `verify-email-confirm.html`/`change-email-confirm.html` para "confirma automáticamente al cargar".
- Página de error genérica nueva para el camino de fallo del ticket 037 (sesión expirada/callback manipulado) — **sin theming de tenant** (sin `--primary-color`, sin `appName`), mensaje claro, enlace de vuelta a un login neutro.
- Verificado en vivo (Claude in Chrome): botón visible solo cuando el proveedor está habilitado para el tenant de prueba; ausente cuando no lo está; flujo de canje exitoso reflejando sesión iniciada; página de error visualmente correcta sin branding.
- Tests de `UiPagesControllerTest`/end-to-end para las rutas nuevas/modificadas; ningún test existente de `login.html`/`register.html` se rompe.
