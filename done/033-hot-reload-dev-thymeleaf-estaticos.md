# 033 — Hot-reload en desarrollo: templates Thymeleaf y recursos estáticos sin reiniciar el JVM

## Objetivo
Hallazgo de tooling encontrado durante el ticket 031: `./gradlew bootRun` cachea los templates de Thymeleaf en memoria y no recopia recursos estáticos (CSS/JS) a `build/resources/main` mientras el proceso sigue vivo — cualquier cambio de template o de estático requiere reiniciar manualmente el JVM para verse reflejado, lo que frena la iteración al verificar cambios en vivo (especialmente en tickets de UI). Se agrega `spring-boot-devtools` + `spring.thymeleaf.cache=false` **solo en el perfil de desarrollo**, sin tocar el build/perfil de producción.

**No incluye:** ningún cambio de comportamiento de producción — es exclusivamente tooling de desarrollo local.

## Criterios de aceptación (TDD)
- `spring-boot-devtools` agregado como dependencia `developmentOnly` en `build.gradle` (nunca empaquetada en el jar de producción — verificar que `bootJar`/`bootBuildImage` la excluye, comportamiento por defecto de la dependencia `developmentOnly` de Spring Boot).
- `spring.thymeleaf.cache=false` (y cualquier config equivalente de cache de recursos estáticos) aplicado únicamente al perfil `dev`/local — el perfil de producción existente no cambia.
- Verificado en vivo: con el servidor corriendo vía `bootRun` en el perfil de dev, un cambio a un `.html` de `templates/` y a un `.css`/`.js` de `static/` se refleja al recargar el navegador, sin reiniciar el proceso manualmente.
- Tests existentes siguen en verde; no se espera comportamiento distinto en el perfil de producción/tests (verificar explícitamente que el perfil de test no activa devtools/cache deshabilitado de forma que afecte assertions existentes).
- `docs/README.md` (o el doc de desarrollo local que corresponda) actualizado con una nota breve de que el hot-reload ya no requiere reinicio manual.

## Hecho
- `spring-boot-devtools` agregado como `developmentOnly` en `backend/build.gradle`, mismo patrón que `spring-boot-docker-compose` ya existente.
- **No hizo falta ninguna config explícita de caché ni separación de perfiles `dev`/`prod` nueva** (el proyecto solo tiene un `application.properties`): devtools, presente en el classpath de `bootRun`, aplica automáticamente sus "property defaults" (`spring.thymeleaf.cache=false`, `spring.freemarker.cache=false`, `spring.web.resources.chain.cache=false`) sin tocar `application.properties`. Confirmado en el log real (`DevToolsPropertyDefaultsPostProcessor : Devtools property defaults active!`).
- **Aislamiento de producción/tests verificado explícitamente**, no solo asumido por convención de `developmentOnly`: inspeccionado el jar empaquetado (`build/libs/auth-core-mc-*.jar`, sin devtools) y `testRuntimeClasspath` (`./gradlew dependencies --configuration testRuntimeClasspath`, sin devtools) — el comportamiento queda confinado a desarrollo local de verdad.
- `docs/README.md`: nota en el paso 3 explicando el hot-reload y el matiz práctico de que, sin una IDE con auto-build, hace falta `./gradlew processResources` (o `-t processResources` en continuo) tras cada edición para que devtools la detecte.
- **Verificado en vivo con `bootRun` real** (no solo el navegador — `curl` antes/después es equivalente para este propósito): editado temporalmente `templates/login.html` y `static/css/app.css`, corrido `processResources`, confirmado en el log el mensaje de devtools reiniciando el contexto (mismo PID de JVM, sin matar/relanzar el proceso), y confirmado con `curl` que el cambio se reflejó sin reinicio manual. Cambios de prueba revertidos antes de terminar.
- **Hallazgo operativo real, resuelto sobre la marcha (no bloqueante):** un proceso `bootRun` viejo (arrancado antes de este cambio, sin devtools en su classpath) seguía ocupando el puerto 8080 — detectado y detenido antes de verificar con el `build.gradle` actualizado.
- `./gradlew clean test`: **BUILD SUCCESSFUL**, todo en verde (incluye `jacocoTestReport`).
- **Propuesta de mejora continua, no implementada (fuera de alcance):** un check/skill de "arranca la app" que verifique si el puerto ya está ocupado por un proceso obsoleto antes de lanzar `bootRun`, ya que este mismo ticket nació de un hallazgo de tooling del ticket 031 y tropezó con ese mismo tipo de problema.
- Detalle completo en `docs/ARQUITECTURA.md` (sección "Ticket 033").
