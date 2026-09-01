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
    healthPorts: [dev: 8081, qa: 8082, prod: 8080],
    vhostFile: 'deploy/vm-infra/nginx/auth-core-mc.conf',
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
