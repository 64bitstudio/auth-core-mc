# 003 — Verificación de cuenta y cambio de correo

## Objetivo
Flujo de verificación de cuenta por correo (link firmado con expiración parametrizable) al registrarse, y flujo de cambio de correo (confirmación en el correo nuevo antes de aplicar el cambio). Proveedor: Resend.

## Criterios de aceptación (TDD)
- El enlace de verificación expira según parámetro configurable por tenant.
- Cambiar de correo no toma efecto hasta confirmar en el correo nuevo.
- Reenvío de verificación con límite de frecuencia (evitar spam).
