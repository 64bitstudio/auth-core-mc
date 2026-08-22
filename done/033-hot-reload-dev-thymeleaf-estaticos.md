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
