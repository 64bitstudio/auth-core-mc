// TEMPORAL -- ticket platform/006, prueba de bypass de branch
// protection con la GitHub App. Se elimina esta rama (y su Jenkinsfile)
// al terminar la verificacion; no es infra real ni permanente.
//
// Reproduce el mismo escenario del incidente real del ticket 002 (push
// directo a una rama protegida) pero deliberado y con el credential
// "github-app" en vez del PAT de Marco -- objetivo: confirmar que
// GitHub RECHAZA el push (a diferencia del PAT, que lo dejaba pasar con
// "Bypassed rule violations").
pipeline {
    agent any
    stages {
        stage('Ticket 006: prueba de bypass con GitHub App') {
            steps {
                sh '''
                    git config user.email "ci-ticket006@64bitstudio.com"
                    git config user.name "ticket-006-bypass-test"
                    echo "prueba de bypass ticket 006 -- $(date -u +%s)" > .ticket-006-bypass-marker
                    git add .ticket-006-bypass-marker
                    git commit -m "test(006): commit de prueba -- no deberia llegar a la rama protegida"
                '''
                script {
                    withCredentials([usernamePassword(credentialsId: 'github-app', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_PAT')]) {
                        sh '''
                            set +e
                            OUT=$(git push "https://${GIT_USER}:${GIT_PAT}@github.com/64bitstudio/auth-core-mc.git" HEAD:refs/heads/ticket-006-bypass-protected 2>&1)
                            CODE=$?
                            echo "$OUT"
                            echo "TICKET006_BYPASS_PUSH_EXIT_CODE=$CODE"
                            if [ "$CODE" -ne 0 ]; then
                                echo "TICKET006_RESULTADO=RECHAZADO (esperado -- la GitHub App NO logro saltarse branch protection)"
                            else
                                echo "TICKET006_RESULTADO=ACEPTADO -- HALLAZGO CRITICO: la GitHub App SI logro saltarse branch protection, igual que el PAT viejo. Reportar de inmediato, no ocultar."
                            fi
                        '''
                    }
                }
            }
        }
    }
}
