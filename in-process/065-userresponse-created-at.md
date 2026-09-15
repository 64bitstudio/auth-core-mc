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
