# 003 — Verificación de cuenta y cambio de correo

## Objetivo
Flujo de verificación de cuenta por correo (link firmado con expiración parametrizable) al registrarse, y flujo de cambio de correo (confirmación en el correo nuevo antes de aplicar el cambio). Proveedor: Resend.

## Criterios de aceptación (TDD)
- El enlace de verificación expira según parámetro configurable por tenant.
- Cambiar de correo no toma efecto hasta confirmar en el correo nuevo.
- Reenvío de verificación con límite de frecuencia (evitar spam).

## Hecho (TDD real: rojo → verde)
- `RedisTokenStore` (genérico, reutilizable en ticket 004) y `Cooldown` — Redis.
- `EmailSender` (interfaz) + `ResendEmailSender` (Resend real, falla explícito sin `RESEND_API_KEY`).
- `EmailVerificationService` / `EmailChangeService` — TTL del tenant, cooldown de reenvío de 60s, revalidación de unicidad al confirmar cambio de correo (por si alguien más tomó el email mientras el link estaba pendiente).
- Endpoints: `/verify-email/request`, `/verify-email/confirm`, `/change-email/request`, `/change-email/confirm`.
- **Límite de confianza temporal documentado explícitamente** (no oculto): `*​/request` reciben `userId` del llamador porque ticket 007 (tokens reales) no existe todavía — impacto acotado a spam de correo, nunca a tomar control de la cuenta. Ver `TenantScopedUserResolver.java` y `docs/API.md`.
- 33 tests nuevos — 91/91 en verde en el proyecto completo.
- Bug de infraestructura encontrado: Spring Boot 4.1 separó `RestClient.Builder` del starter `webmvc` en su propio starter `spring-boot-starter-restclient` — sin él, `ResendEmailSender` no arranca (`NoSuchBeanDefinitionException`). Documentado en `ARQUITECTURA.md`.
- **Nota de proceso**: a partir de este ticket se adopta flujo de rama por ticket + PR (otra sesión instaló un hook `main-branch-guard` que lo exige); tickets 001-002 se habían commiteado directo a `main` antes de que existiera esa regla.
