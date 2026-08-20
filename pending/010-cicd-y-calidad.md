# 010 — CI/CD y calidad para auth-core-mc

## Objetivo
`.github/workflows/ci.yml`: build + tests + análisis SonarQube (contra la instancia local de `~/dev-infra`) + notificación a Telegram al finalizar (éxito/fallo, link a resultados).

## Criterios de aceptación
- Todo push a una rama abre chequeo automático.
- Falla el pipeline si SonarQube reporta Quality Gate en rojo.
- Notificación a Telegram con el resultado.
