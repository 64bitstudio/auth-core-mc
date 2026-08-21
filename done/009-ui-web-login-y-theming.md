# 009 — UI web (login, registro, 2FA, reset) con theming

## Objetivo
Interfaz web para todos los flujos de usuario final: login, registro, verificación, 2FA, reset de contraseña, cambio de correo. Personalizable por tenant (`primary_color`, `app_name`, logo).

## Criterios de aceptación (TDD/E2E)
- Cambiar `primary_color`/`app_name` de un tenant sin tocar código, solo configuración.
- Accesible (labels, contraste, navegación por teclado).
- Documentado en `docs/COMPONENTES.md` y `docs/README.md` (dónde y cómo tocar cada parámetro).

## Hecho
- Stack: Thymeleaf server-rendered (no SPA separado) — decisión ya implícita en `ARQUITECTURA.md` sección 8, ver el porqué completo en `docs/COMPONENTES.md`.
- 7 páginas implementadas: registro, login, "mi cuenta" (hub de verificación/cambio de correo/2FA), solicitud de reset, y las tres páginas de confirmación por token (verify-email, change-email, password-reset). Cada una llama a la API JSON ya existente vía `fetch` — ninguna lógica de negocio nueva en el backend, salvo `UiPagesController` (resuelve tenant para theming) y el cambio de destino de los enlaces emailados (`VerificationLinkFactory`, ahora apunta a `/ui/**` en vez de a los endpoints JSON crudos).
- Criterio "cambiar `primary_color`/`app_name` sin tocar código": cumplido — `UiPagesController` resuelve el `Tenant` en cada request y lo inyecta en la plantilla; es una fila de la tabla `tenant`, nunca una constante.
- Criterio "accesible": labels asociados a cada input + `aria-label` redundante, skip-link, foco visible, `role="alert"`/`role="status"` con `aria-live`, paleta con contraste AA. Verificado manualmente contra WCAG, **no** con una herramienta automatizada (axe-core/pa11y) — este proyecto no tiene toolchain de JS; hueco de cobertura documentado explícitamente en `docs/COMPONENTES.md`, no silenciado.
- `UiPagesControllerTest` (7 tests): renderizado + theming correcto por tenant, rechazo de `client_id` desconocido, páginas de confirmación públicas sin `client_id`.
- Verificado en vivo en un navegador real (Claude-in-Chrome): registro → redirección a `/ui/cuenta` → reenvío de verificación (cooldown de 60s respetado) → enroll de TOTP (secreto real generado y mostrado en pantalla) → login con las mismas credenciales → redirección a `/ui/cuenta`. Páginas de confirmación probadas sin `token` (fallan con mensaje claro, no un error genérico).
- 181/181 tests automatizados en verde (174 previos + 7 nuevos de `UiPagesControllerTest`).
- Hallazgo de secuenciación (igual patrón que ticket `006`): esta UI NO está integrada con el flujo `/oauth2/authorize` (Authorization Code+PKCE) de Spring Authorization Server — su `formLogin` sigue usando el formulario por defecto de Spring Security, sin `UserDetailsService` real. Resolverlo requiere decidir un diseño genuino (cómo un login form compartido entre tenants sabe contra qué tenant autenticar, dado que el email es único por tenant, no global) — documentado como pendiente en `ARQUITECTURA.md`, no resuelto con una suposición apresurada ni silenciado.
- Documentación actualizada: `docs/COMPONENTES.md` (reescrito completo), `docs/ARQUITECTURA.md` (sección del ticket 009), `docs/README.md` (cómo probar la UI manualmente), `docs/API.md` (pointer a COMPONENTES.md).
