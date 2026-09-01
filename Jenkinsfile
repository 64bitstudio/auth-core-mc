// Ticket 002 de platform (Shared Library): este Jenkinsfile queda
// reducido a invocar corePipeline() con los parámetros propios de
// auth-core-mc -- el pipeline completo (build/test/Sonar/Quality
// Gate/build de imagen/vhost/deploy dev-qa-prod/gate manual de
// prod/cleanup.sh/notificación a Telegram) vive ahora en
// 64bitstudio/platform (vars/corePipeline.groovy), registrado como
// Global Pipeline Library "platform" vía JCasC. Mismo comportamiento
// EXACTO que el Jenkinsfile anterior (ticket 049) -- ver su historial de
// git para comparar campo a campo. Verificado con un deploy real a DEV
// (ver docs/ARQUITECTURA.md de platform, runbook "conectar un proyecto
// nuevo", para la evidencia).

@Library('platform') _

corePipeline(
    projectName: 'auth-core-mc',
    // containerPort no se pasa -- default de corePipeline (8080) ya es
    // el puerto interno real en dev/qa/prod (ver
    // deploy/docker-compose.*.yml: "8081:8080"/"8082:8080"/"8080:8080"
    // -- el puerto publicado al host varía, el interno no).
    vhostFile: 'deploy/vm-infra/nginx/auth-core-mc.conf',
    // Incidente real (ver platform/docs/ARQUITECTURA.md): el archivo de
    // arriba, tal como vive en git, es la versión solo-HTTP -- sin esto,
    // cada deploy a dev pisaría el bloque 443/ssl real (agregado por
    // certbot en vivo, nunca sincronizado a git) con esa versión,
    // rompiendo HTTPS de los 3 subdominios.
    certbotDomains: ['auth.64bitstudio.com', 'auth-qa.64bitstudio.com', 'auth-dev.64bitstudio.com'],
    buildAndTest: {
        // Hallazgo real (primer build real de dev, heredado sin cambios):
        // la imagen de Jenkins solo trae JDK 21 (para correr Jenkins
        // mismo) -- el backend necesita el toolchain Java 25. Se activa
        // SOLO para este closure, sin tocar el JDK del controller.
        withEnv([
            "JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-arm64",
            "PATH=/usr/lib/jvm/temurin-25-jdk-arm64/bin:${env.PATH}"
        ]) {
            dir('backend') {
                withSonarQubeEnv('sonarqube-vm') {
                    sh './gradlew build sonar'
                }
            }
        }
    }
)
