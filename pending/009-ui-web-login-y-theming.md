# 009 — UI web (login, registro, 2FA, reset) con theming

## Objetivo
Interfaz web para todos los flujos de usuario final: login, registro, verificación, 2FA, reset de contraseña, cambio de correo. Personalizable por tenant (`primary_color`, `app_name`, logo).

## Criterios de aceptación (TDD/E2E)
- Cambiar `primary_color`/`app_name` de un tenant sin tocar código, solo configuración.
- Accesible (labels, contraste, navegación por teclado).
- Documentado en `docs/COMPONENTES.md` y `docs/README.md` (dónde y cómo tocar cada parámetro).
