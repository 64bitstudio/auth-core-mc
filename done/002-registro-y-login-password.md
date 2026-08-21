# 002 — Registro y login con email/teléfono + contraseña

## Objetivo
Endpoints REST para registro (email o teléfono + password) y login directo (first-party, ver ticket 007 para el flujo redirect). Hash Argon2id. Validaciones de fuerza de contraseña y de formato de teléfono/email.

## Criterios de aceptación (TDD)
- No permitir registro sin al menos un identificador (email o teléfono).
- No permitir duplicados dentro del mismo tenant.
- Rate limiting de intentos de login (Redis) para mitigar fuerza bruta.

## Hecho (TDD real: rojo → verde)
- Decisión resuelta (quedaba pendiente desde el ticket 001): el tenant de cada request se identifica vía header `X-Client-Id`. Documentado en `docs/API.md` y `docs/ARQUITECTURA.md`.
- `PasswordPolicy` (mín. 8 caracteres, letra+dígito) e `IdentifierFormat` (email/E.164) — validación pura, sin Spring.
- `PasswordEncoderConfig` — Argon2id vía Spring Security (`Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()`).
- `LoginRateLimiter` — Redis, 5 intentos/15 min por tenant+identificador, se resetea en éxito.
- `RegistrationService` / `AuthenticationService` — orquestan validación, hashing y traducción de excepciones de infraestructura a excepciones de dominio.
- `RegistrationController` (`POST /register`) y `AuthController` (`POST /login`), con `GlobalExceptionHandler` mapeando cada excepción de dominio a su código HTTP.
- `SecurityConfig` mínimo — placeholder hasta que ticket 007 traiga la configuración real de Spring Authorization Server.
- **Login no emite tokens todavía** — decisión explícita de alcance, ver `docs/ARQUITECTURA.md` sección del ticket 002.
- 38 tests nuevos (unitarios de `PasswordPolicy`/`IdentifierFormat`, Mockito para los servicios, Testcontainers/Redis para el rate limiter, `@WebMvcTest` para los controladores) — 58/58 en verde en el proyecto completo.
- Bugs propios detectados y corregidos en el camino: `Tenant.getId()` es `null` en fixtures no persistidos (afecta cualquier test unitario que use el tenant como llave), y `@WebMvcTest` no carga `SecurityConfig` sin `@Import` explícito (devolvía 403 en todos los tests de controlador). Ambos documentados en `ARQUITECTURA.md` para no repetirlos.
