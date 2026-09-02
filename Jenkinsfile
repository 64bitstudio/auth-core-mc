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

// Ticket platform/004 (docs/definiciones/vault-secrets-manager-vm.md,
// HU-1/HU-3/HU-4): a partir de este commit, DB_PASSWORD de dev/qa/prod
// ya no viene del .env estático -- corePipeline.groovy lo obtiene de
// Vault (AppRole "jenkins-infra", solo lectura) y lo renderiza en el
// archivo real antes de cada deploy. Este comentario existe para
// forzar un push real a `dev` y verificar de punta a punta que
// fetchAndPatchDbPasswordFromVault corre en un build real de Jenkins
// -- ver platform/docs/ARQUITECTURA.md, ticket 004, para la evidencia
// una vez verificado.
//
// Segunda verificación (mismo ticket 004): el primer deploy real
// (build #11) expuso el token de Vault en texto plano en el log de
// Jenkins (set -x de la shell imprimía la línea del curl con el token
// ya resuelto) -- corregido en platform con `set +x` explícito
// (vars/corePipeline.groovy). Este comentario fuerza un segundo push
// real a `dev` para confirmar que el fix realmente quita el token del
// log, no solo que el código se ve bien.

// Ticket platform/006 (docs/definiciones/vault-secrets-manager-vm.md,
// HU-9): el checkout de este Jenkinsfile (y de la Shared Library
// "platform") ya no usa el PAT compartido de la cuenta personal de
// Marco -- usa la GitHub App "64bitstudio-jenkins-ci" (credential
// "github-app", ver platform/deploy/vm-infra/jenkins/casc/jenkins.yaml)
// desde que este comentario se agregó. Motivo real: un PAT, sin
// importar qué tan acotado, sigue heredando el bypass de branch
// protection de la cuenta de Marco (owner de la organización) -- la
// GitHub App tiene su propia identidad, nunca lo hereda (verificado en
// vivo, ver platform/docs/ARQUITECTURA.md ticket 006: rama de prueba
// con la misma protección real de `dev`, push directo con un
// installation token real de esta App -- RECHAZADO por GitHub, a
// diferencia del PAT viejo). Este comentario fuerza un push real a
// `dev` para confirmar que el checkout con el credential nuevo sigue
// funcionando sin regresión (mismo criterio que las dos verificaciones
// anteriores de este archivo).
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
