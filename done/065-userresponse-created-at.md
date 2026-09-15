# 065 — `UserResponse` gana `createdAt`

## Objetivo
El ticket `093` de galgoth-studio ("Pantalla Usuario") necesita mostrar
"Miembro desde {fecha}" en la cabecera del perfil — dato ya existente
(`User.createdAt`, auditoría desde el ticket `001`) pero nunca expuesto
en `UserResponse`, la respuesta que usan `GET /api/v1/account/profile`
y el resto de endpoints de cuenta.

## Alcance
- **Sí incluye:** agregar `createdAt` (Instant) a `UserResponse` —
  cambio puramente aditivo (nuevo campo en un record JSON), sin tocar
  ningún endpoint ni consumidor existente.
- **No incluye:** ningún cambio de comportamiento — accediendo desde
  cualquier respuesta que ya use `UserResponse` (login, registro,
  perfil, etc.), todas siguen funcionando igual, solo con un campo más.

## Criterios de aceptación (TDD)
- `GET /api/v1/account/profile` incluye `createdAt` en la respuesta.
- Suite completa en verde.

## Hecho
`UserResponse.java`: nuevo campo `Instant createdAt`, poblado en `.from()` vía `user.getCreatedAt()` — ya existía en `User` (auditoría desde ticket 001), solo faltaba exponerlo.

Tests: +1 en `AccountProfileControllerTest` (`GET /api/v1/account/profile` incluye `createdAt`). Docs: una línea en `docs/API.md`.

CI de Jenkins verde (PR #122, mergeado). Suite completa en verde. Verificado en vivo contra dev como parte de la verificación de galgoth-studio#093: el campo llega poblado con la fecha real de creación del usuario, y "Miembro desde septiembre de 2026" se renderiza correctamente en la cabecera de la Pantalla "Usuario".
