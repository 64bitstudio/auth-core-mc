# 010 — CI/CD y calidad para auth-core-mc

## Objetivo
`.github/workflows/ci.yml`: build + tests + análisis SonarQube (contra la instancia local de `~/dev-infra`) + notificación a Telegram al finalizar (éxito/fallo, link a resultados).

## Criterios de aceptación
- Todo push a una rama abre chequeo automático.
- Falla el pipeline si SonarQube reporta Quality Gate en rojo.
- Notificación a Telegram con el resultado.

## Hecho
- **Impedimento real identificado y resuelto con el Product Owner**: un runner de GitHub Actions en la nube no puede alcanzar el SonarQube local (`~/dev-infra`, `localhost:9000`). Se preguntó explícitamente (self-hosted runner / sin SonarQube por ahora / SonarCloud); se eligió self-hosted runner.
- Self-hosted runner registrado y corriendo como agente de usuario (`launchd`, sin `sudo`) en la Mac del Product Owner — online y confirmado en GitHub antes de escribir el workflow.
- `SONAR_TOKEN` no existía (`~/dev-infra/.env` lo tenía vacío) — generado vía la API de SonarQube y persistido tanto en `.env` (reuso futuro) como en los secretos de GitHub Actions de este repo, con autorización explícita del Product Owner para manipular credenciales (acción bloqueada primero por el clasificador de auto-mode, correctamente, por tratarse de mover credenciales).
- `.github/workflows/ci.yml`: build+test, análisis SonarQube (`./gradlew sonar`), Quality Gate real vía la acción oficial `sonarsource/sonarqube-quality-gate-action@v1.2.1` (fijada a un tag de release, no `@master`) que sí falla el job si el gate está en rojo, y notificación a Telegram (`if: always()`, éxito o fallo, con link al run).
- `build.gradle`: plugin `org.sonarqube` 7.4.0.8496 + bloque `sonar { properties { ... } }` con `projectKey`/`projectName`.
- Criterio "todo push abre chequeo automático": cumplido — `on: push: branches: ["**"]` + `pull_request`.
- Criterio "falla si Quality Gate en rojo": cumplido vía la acción oficial de SonarSource (no confiar solo en `./gradlew sonar`, que solo envía el análisis sin bloquear).
- Criterio "notificación a Telegram con el resultado": cumplido, reusa `~/dev-infra/scripts/notify.sh`.
- Verificado en vivo, no solo escrito: `./gradlew build sonar` corrido localmente antes de empujar (Quality Gate confirmado `OK` vía API de SonarQube), y la ejecución real del workflow observada en GitHub Actions contra el runner recién registrado.
- Documentación actualizada: `docs/ARQUITECTURA.md` (decisión completa + alternativas evaluadas), `docs/README.md` (cómo operar/reinstalar el runner, dónde viven los secretos).
