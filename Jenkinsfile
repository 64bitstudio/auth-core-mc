// Ticket 049 (pivote GH Actions -> Jenkins, 2026-08-30): orquestador del
// pipeline. Reemplaza a .github/workflows/ci.yml -- que se mantiene
// corriendo EN PARALELO hasta confirmar que este Jenkinsfile funciona de
// punta a punta (no se retira ci.yml todavía, decisión explícita).
// Reusa exactamente los mismos artefactos ya construidos en el ticket:
// backend/Dockerfile, deploy/docker-compose.{dev,qa,prod}.yml,
// deploy/cleanup.sh, deploy/.env.{dev,qa,prod} (fuera del checkout, en
// /home/ubuntu/secrets/auth-core-mc/ -- montado 1:1 en el contenedor de
// Jenkins, ver deploy/vm-infra/jenkins/docker-compose.yml).
//
// Corre en el propio nodo controller de Jenkins (agent any -- único
// executor de esta instalación, ver deploy/vm-infra/jenkins/), con
// acceso directo al Docker daemon REAL de la VM vía docker.sock (no
// Docker-in-Docker, ver el porqué en deploy/vm-infra/jenkins/
// docker-compose.yml).
//
// Flujo de ramas (idéntico al de ci.yml, ver docs/ARQUITECTURA.md
// ticket 049): feature/NNN -> dev (auto) -> qa (merge manual de Marco,
// mergeado por el orquestador) -> prod (gate `input` de este mismo
// Jenkinsfile, restringido a Marco -- NO un merge de PR como en el
// modelo anterior; al aprobar, este job también actualiza la rama
// `prod` en git para que el historial siga reflejando la realidad).
//
// Duplicación temporal aceptada mientras ambos pipelines corren en
// paralelo: build-test-analyze de ci.yml y el stage de Sonar de aquí
// analizan el mismo commit dos veces. Se resuelve al retirar ci.yml.

pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    stages {
        stage('Resolver SHA del commit') {
            // El env var estándar del plugin de git (GIT_COMMIT) no
            // queda garantizado en un Multibranch Pipeline con checkout
            // ligero -- se resuelve explícito, una sola vez, en vez de
            // asumirlo.
            steps {
                script {
                    env.GIT_SHA = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                }
            }
        }

        stage('Build, test y análisis SonarQube') {
            steps {
                dir('backend') {
                    withSonarQubeEnv('sonarqube-vm') {
                        sh './gradlew build sonar'
                    }
                }
            }
        }

        stage('Quality Gate de SonarQube') {
            steps {
                // Requiere el webhook Sonar -> Jenkins ya configurado
                // (ver el step "Webhook de SonarQube -> Jenkins" en
                // ci.yml, sync-vm-infra) -- sin él, esto se cuelga hasta
                // el timeout.
                timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage('Build de la imagen (tag = SHA del commit)') {
            when { branch 'dev' }
            steps {
                sh """
                    docker build \\
                        --label org.opencontainers.image.revision=${env.GIT_SHA} \\
                        -t auth-core-mc:${env.GIT_SHA} \\
                        ./backend
                """
            }
        }

        stage('Deploy a DEV') {
            when { branch 'dev' }
            steps {
                sh """
                    docker tag auth-core-mc:${env.GIT_SHA} auth-core-mc-dev:${env.GIT_SHA}
                    docker tag auth-core-mc:${env.GIT_SHA} auth-core-mc-dev:current
                    IMAGE_TAG=current docker compose -f deploy/docker-compose.dev.yml --env-file /home/ubuntu/secrets/auth-core-mc/.env.dev up -d
                """
                sh """
                    for i in \$(seq 1 30); do
                        if curl -sf http://localhost:8081/actuator/health | grep -q '"status":"UP"'; then
                            echo "DEV healthy."; exit 0
                        fi
                        echo "Esperando a que DEV quede healthy... (\$i/30)"; sleep 5
                    done
                    echo "DEV nunca quedó healthy." >&2; exit 1
                """
                sh 'IMAGE_TAG=current docker compose -f deploy/docker-compose.dev.yml --env-file /home/ubuntu/secrets/auth-core-mc/.env.dev ps'
                sh './deploy/cleanup.sh dev'
            }
        }

        stage('Deploy a QA') {
            when { branch 'qa' }
            steps {
                script {
                    env.QA_SHA = sh(
                        script: "docker inspect --format '{{ index .Config.Labels \"org.opencontainers.image.revision\" }}' auth-core-mc-dev:current",
                        returnStdout: true
                    ).trim()
                    if (!env.QA_SHA) {
                        error('No se encontró auth-core-mc-dev:current -- ¿corrió el deploy a DEV alguna vez?')
                    }
                }
                sh """
                    docker tag auth-core-mc-dev:current auth-core-mc-qa:${env.QA_SHA}
                    docker tag auth-core-mc-dev:current auth-core-mc-qa:current
                    IMAGE_TAG=${env.QA_SHA} docker compose -f deploy/docker-compose.qa.yml --env-file /home/ubuntu/secrets/auth-core-mc/.env.qa up -d
                """
                sh """
                    for i in \$(seq 1 30); do
                        if curl -sf http://localhost:8082/actuator/health | grep -q '"status":"UP"'; then
                            echo "QA healthy."; exit 0
                        fi
                        echo "Esperando a que QA quede healthy... (\$i/30)"; sleep 5
                    done
                    echo "QA nunca quedó healthy." >&2; exit 1
                """
                sh "IMAGE_TAG=${env.QA_SHA} docker compose -f deploy/docker-compose.qa.yml --env-file /home/ubuntu/secrets/auth-core-mc/.env.qa ps"
                sh './deploy/cleanup.sh qa'
            }
        }

        // === GATE MANUAL DE PROD -- exclusivo de Marco, sin excepción ===
        // A diferencia del modelo anterior (merge de PR a una rama
        // `prod`), aquí el gate es un `input` real de Jenkins: pausa el
        // pipeline y muestra un botón en su UI. `submitter` restringe
        // quién puede apretarlo -- solo el usuario `marco` (ver
        // deploy/vm-infra/jenkins/casc/jenkins.yaml). Nadie más, ni este
        // Jenkinsfile, puede aprobarlo solo.
        stage('¿Promover a PROD?') {
            when { branch 'qa' }
            steps {
                script {
                    def aprobado = false
                    try {
                        timeout(time: 7, unit: 'DAYS') {
                            input message: "¿Promover auth-core-mc:${env.QA_SHA} (validado en QA) a PROD?",
                                  submitter: 'marco',
                                  ok: 'Promover a PROD'
                        }
                        aprobado = true
                    } catch (err) {
                        currentBuild.result = 'ABORTED'
                        error("Promoción a PROD no aprobada (rechazada o expiró el plazo): ${err}")
                    }
                    env.PROD_APROBADO = aprobado.toString()
                }
            }
        }

        stage('Deploy a PROD (sin rebuild)') {
            when {
                allOf {
                    branch 'qa'
                    environment name: 'PROD_APROBADO', value: 'true'
                }
            }
            steps {
                sh """
                    docker tag auth-core-mc-qa:current auth-core-mc-prod:${env.QA_SHA}
                    docker tag auth-core-mc-qa:current auth-core-mc-prod:current
                    IMAGE_TAG=${env.QA_SHA} docker compose -f deploy/docker-compose.prod.yml --env-file /home/ubuntu/secrets/auth-core-mc/.env.prod up -d
                """
                sh """
                    for i in \$(seq 1 30); do
                        if curl -sf http://localhost:8080/actuator/health | grep -q '"status":"UP"'; then
                            echo "PROD healthy."; exit 0
                        fi
                        echo "Esperando a que PROD quede healthy... (\$i/30)"; sleep 5
                    done
                    echo "PROD nunca quedó healthy." >&2; exit 1
                """
                sh "IMAGE_TAG=${env.QA_SHA} docker compose -f deploy/docker-compose.prod.yml --env-file /home/ubuntu/secrets/auth-core-mc/.env.prod ps"
                sh './deploy/cleanup.sh prod'

                // Registro en git: la rama `prod` avanza al mismo commit
                // que se acaba de promover -- el historial de ramas
                // sigue reflejando la realidad, aunque el despliegue en
                // sí ya haya ocurrido vía este pipeline, no vía un merge.
                withCredentials([usernamePassword(credentialsId: 'github-pat', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_PAT')]) {
                    sh """
                        git push https://\${GIT_USER}:\${GIT_PAT}@github.com/64bitstudio/auth-core-mc.git HEAD:refs/heads/prod
                    """
                }
            }
        }
    }

    post {
        always {
            script {
                if (env.TELEGRAM_BOT_TOKEN?.trim()) {
                    def emoji = currentBuild.currentResult == 'SUCCESS' ? '✅' : '🔴'
                    def msg = "*${emoji} [auth-core-mc] Jenkins ${env.BRANCH_NAME}*\n${currentBuild.currentResult} -- ${env.BUILD_URL}"
                    sh """
                        curl -sS -X POST "https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/sendMessage" \\
                            -d chat_id=${env.TELEGRAM_CHAT_ID} \\
                            -d parse_mode=Markdown \\
                            --data-urlencode "text=${msg}" \\
                            > /dev/null || true
                    """
                }
            }
        }
    }
}
