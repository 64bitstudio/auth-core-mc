# Componentes de la interfaz — auth-core-mc

> Estructura de pantallas y componentes de la UI web. Se actualiza al completar el ticket `009-ui-web-login-y-theming` y cualquier ticket posterior de UI.

## Estado actual
**Implementado y probado** (ticket `009`, en `/done`). Server-rendered con Thymeleaf — sin build de frontend, sin npm — para que un solo `./gradlew bootRun` levante backend y UI juntos. Cada página es un formulario que llama a la misma API JSON documentada en `API.md` vía `fetch()` (`static/js/api.js`); ninguna lógica de negocio vive en las plantillas ni en `UiPagesController`.

## Por qué Thymeleaf server-rendered, no un SPA aparte
Un servidor de autorización OAuth2 (Auth0, Keycloak, Spring Authorization Server) sirve sus propias pantallas de login/consentimiento — esa decisión ya estaba tomada desde `ARQUITECTURA.md` sección 8. Un SPA separado (React/Vue + build propio) habría significado un segundo deployable, CORS entre orígenes, y duplicar el theming en dos lugares. Con Thymeleaf, el mismo proceso Spring Boot resuelve el tenant (por `client_id`) y renderiza el CSS con sus colores **antes** de que la página llegue al navegador — el theming es tan simple como una variable CSS inyectada en el HTML.

## Pantallas implementadas

| Pantalla | Ruta | Necesita `client_id` | Qué hace |
|---|---|---|---|
| Registro | `/ui/register` | Sí (query param) | Formulario email/teléfono + password + nombre/apellidos → `POST /api/v1/register` |
| Login | `/ui/login` | Sí | Formulario identifier + password → `POST /api/v1/login`; guarda la sesión de conveniencia (ver abajo) y redirige a `/ui/cuenta` |
| Mi cuenta | `/ui/cuenta` | Sí | Hub post-login/registro: estado de verificación de correo + botón de reenvío, establecer contraseña (solo cuenta social-only, ticket `041`), cambio de correo, OTP (request/verify), enroll/verify de TOTP, selector de método de 2FA preferido |
| Olvidé mi contraseña | `/ui/password-reset/request` | Sí | Formulario de identifier → `POST /api/v1/password-reset/request` (mismo mensaje siempre, ver `API.md`) |
| Restablecer contraseña | `/ui/password-reset/confirm` | No (usa `token` de la URL) | Formulario de nueva contraseña |
| Verificar correo | `/ui/verify-email/confirm` | No (usa `token`) | Confirma automáticamente al cargar |
| Confirmar cambio de correo | `/ui/change-email/confirm` | No (usa `token`) | Confirma automáticamente al cargar |

## `client_id` como query param, no como header — y por qué las páginas de confirmación no llevan theming
La API JSON usa el header `X-Client-Id` (ticket `002`), pero una navegación de página completa (escribir una URL, dar clic en un enlace, abrir un correo) no puede fijar un header propio — solo el JS que corre DESPUÉS de cargar la página puede hacerlo (y lo hace, para sus propias llamadas a `fetch`). Por eso toda página que necesita theming lleva `?client_id=...` en su URL, igual que ya hace `/oauth2/authorize` (ticket `007`) por la misma razón.

Los enlaces enviados por correo (verificación, cambio de correo, reset) solo llevan un `token` de un solo uso — nunca un `client_id` — porque un tenant puede tener más de un `identity_client`, y no hay un único `app_name`/`primary_color` "correcto" para asociar a un token suelto. Esas tres páginas se muestran sin el color de marca del tenant (branding neutro); es una limitación deliberada, no un olvido.

## La "sesión" de `/ui/cuenta` es del navegador, no del servidor
`/ui/cuenta` necesita un `userId` para reenviar verificación, cambiar correo, o tocar 2FA — pero pedirle a una persona que escriba su propio UUID sería mala UX y no aporta nada. En vez de eso, un `/ui/register` o `/ui/login` exitoso guarda `userId`/`email`/`emailVerified` en `sessionStorage` (ver `static/js/api.js`), y `/ui/cuenta` los lee de ahí; si no hay nada guardado, redirige a `/ui/login`.

**Esto es una conveniencia del lado del cliente, no una sesión real impuesta por el servidor** — ninguna de estas rutas está protegida por Spring Security más allá de estar en `permitAll` (ver `SecurityConfig`). Es la misma frontera de confianza temporal que los tickets `003`/`005` ya documentaron para estos mismos endpoints (el peor caso es que alguien con el `userId` de otra persona dispare un reenvío de correo — spam acotado por cooldown, no una cuenta comprometida), extendida aquí a la UI en vez de resuelta. Una integración real (que `/ui/cuenta` dependa de un access token de ticket `007` en vez de `sessionStorage`) queda pendiente — ver la nota de secuenciación en `ARQUITECTURA.md` ticket `009`.

### Excepción: "Establecer contraseña" sí usa el access token real (ticket `041`)
La acción nueva de HU-5 (`docs/definiciones/login-social-real.md`) es la primera de `/ui/cuenta` en apoyarse en el `accessToken` real guardado en `sessionStorage` (`AuthCoreUi.callAuthenticated`, no `AuthCoreUi.call`) en vez del `userId` de conveniencia — su endpoint (`POST /api/v1/account/password`, ver `API.md`) no está en `permitAll`, así que cae bajo el mismo `oauth2ResourceServer`/`.anyRequest().authenticated()` que ya protege el panel de administración. Se decidió así porque, a diferencia de reenviar un correo o pedir un OTP, establecer una password surte efecto de inmediato y sin un segundo factor que la acote — el trust boundary "temporal" del resto de esta página no era aceptable aquí. El botón se muestra solo cuando `AuthCoreUi.currentSnapshot().hasPassword` es `false` (campo nuevo de `UserResponse`, ver `API.md`).

## Theming por tenant
`UiPagesController` resuelve el `Tenant` por `client_id` (reusando `ClientContextResolver.resolveTenant`, el mismo que usa la API JSON) y pasa `appName`/`primaryColor`/`clientId` al modelo de Thymeleaf. Cada plantilla fija `--primary-color` como variable CSS inline en el `<html>` y usa `${appName}` en el `<title>`/encabezado — cambiar el color o nombre de un tenant es una fila en la tabla `tenant` (ver `BASE_DE_DATOS.md`), nunca tocar código ni volver a desplegar. El logo por tenant queda pendiente de que se defina un mecanismo de subida de assets (no había un requisito concreto que resolver todavía).

## Accesibilidad — qué se verificó y qué no
Cada input tiene `<label for="...">` + `aria-label` redundante, hay un "saltar al contenido" (`.skip-link`) visible al enfocar con teclado, los estados de foco tienen contorno visible de 3px, los mensajes de error/éxito usan `role="alert"`/`role="status"` con `aria-live="polite"` (anunciados por lectores de pantalla sin robar el foco), y la paleta por defecto (texto `#1a1a1a` sobre blanco, error `#7a1518` sobre `#fdecea`) pasa contraste WCAG AA. **Esto se verificó manualmente y contra la especificación WCAG, no con una herramienta automatizada tipo axe-core/pa11y** — este proyecto no tiene toolchain de JavaScript/npm, y añadir uno solo para una auditoría de accesibilidad no se justificaba en el alcance de este ticket. Queda como un hueco de cobertura conocido y documentado, no silenciado.

⚠️ El theming por tenant sobreescribe `--primary-color`, pero no valida que el color configurado tenga contraste suficiente contra blanco/texto oscuro — un tenant que configure un `primary_color` muy claro podría terminar con botones de bajo contraste. No hay validación de esto todavía (ni en el modelo `Tenant` ni en la UI).

## Verificado en vivo (no solo con tests)
Además de `UiPagesControllerTest` (renderizado + theming vía MockMvc), se probó el flujo completo en un navegador real contra la app corriendo: registro → redirección a `/ui/cuenta` → reenvío de verificación (respeta el cooldown de 60s) → enroll de TOTP (secreto real generado y mostrado) → logout implícito → login con las mismas credenciales → redirección a `/ui/cuenta`. Las páginas de confirmación (`/ui/verify-email/confirm`, etc.) se probaron sin `token` para confirmar que fallan con un mensaje claro en vez de un error genérico.
