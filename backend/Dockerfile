# Ticket 049: imagen Docker del backend, construida y ejecutada en el mismo
# host (la VM OCI Ampere A1.Flex, arquitectura ARM64) — sin registry externo,
# ver docs/ARQUITECTURA.md ticket 049 para el porqué.
#
# Build multi-stage: la etapa "build" nunca llega a la imagen final (ni el
# JDK completo, ni el árbol de fuentes, ni la caché de Gradle) — solo el jar
# resultante se copia a la etapa "runtime", que usa un JRE (no JDK).
#
# Sin ejecutar tests aquí (`-x test`): el job `build-image` del workflow ya
# corre después del job de tests unitarios/SonarQube — repetir los tests
# dentro del build de la imagen los correría dos veces y, más importante,
# requeriría Docker-in-Docker para los tests que usan Testcontainers
# (Postgres/Redis reales) — complejidad innecesaria cuando el runner que
# construye esta imagen YA es el mismo host Docker.
#
# eclipse-temurin es multi-arch (amd64/arm64) — la misma sintaxis de
# Dockerfile funciona igual en la Mac (arm64 también, dato curioso) y en la
# VM Ampere (arm64), sin flags de plataforma especiales.

FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /app

# Copiar primero el wrapper y el manifiesto de dependencias — capa cacheable
# por Docker mientras no cambien, así un cambio en src/ no fuerza redescargar
# el mundo de Gradle en cada build.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon help >/dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test -x sonar

FROM eclipse-temurin:25-jre-noble AS runtime

# curl: usado por el healthcheck de docker-compose.{test,prod}.yml contra
# /actuator/health — no viene en la imagen base de Temurin.
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Usuario no-root — la imagen nunca corre la app como root.
RUN groupadd --system app && useradd --system --gid app --no-create-home app

WORKDIR /app
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar
RUN chown app:app app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
