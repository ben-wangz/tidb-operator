/*
 * Copyright 2020. PingCAP, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import groovy.transform.Field

@Field
def values = '''
controllerManager:
  replicas: 2
scheduler:
  replicas: 2
admissionWebhook:
  create: true
  replicas: 2
  validation:
    statefulSets: true
    pingcapResources: false
  mutation:
    pingcapResources: true
  failurePolicy:
    validation: Fail
    mutation: Fail
'''

def call(BUILD_BRANCH) {
    def GITHASH
    def FILE_SERVER_URL = "http://fileserver.pingcap.net"
    def FILE_BASE_DIR = "pingcap/tidb-operator"

    catchError {
        node('delivery') {
            container('delivery') {
                def WORKSPACE = pwd()
                sh "chown -R jenkins:jenkins ./"
                deleteDir()

                dir("${WORKSPACE}/operator-staging") {
                    stage('Download tidb-operator binary') {
                        GITHASH = sh(returnStdout: true, script: "curl ${FILE_SERVER_URL}/download/${FILE_BASE_DIR}/refs/${BUILD_BRANCH}/sha1").trim()
                        sh "curl ${FILE_SERVER_URL}/download/${FILE_BASE_DIR}/builds/${GITHASH}/tidb-operator.tar.gz | tar xz"
                    }
                    stage('Push tidb-operator Docker Image to Harbor') {
                        docker.withRegistry("https://hub.pingcap.net", "harbor-pingcap") {
                            docker.build("hub.pingcap.net/jenkins/tidb-operator:${GITHASH}", "--build-arg=TARGETARCH=amd64 images/tidb-operator").push()
                        }
                    }

                    stage('Deploy tidb-operator to staging') {
                        ansiColor('xterm') {
                            writeFile file: 'values.yaml', text: "${values}"
                            sh """
                            # ensure helm
                            wget https://storage.googleapis.com/kubernetes-helm/helm-v2.17.0-linux-amd64.tar.gz
                            tar -zxvf helm-v2.17.0-linux-amd64.tar.gz
                            chmod +x linux-amd64/helm

                            # deploy to staging
                            export KUBECONFIG=/home/jenkins/.kubeconfig/operator_staging
                            ./linux-amd64/helm upgrade --install tidb-operator charts/tidb-operator --namespace=tidb-admin --set-string operatorImage=hub.pingcap.net/jenkins/tidb-operator:${GITHASH} -f values.yaml
                            """
                        }
                    }
                }
            }
        }
        currentBuild.result = "SUCCESS"
    }

    stage('Summary') {
        echo("echo summary info ########")
        def DURATION = ((System.currentTimeMillis() - currentBuild.startTimeInMillis) / 1000 / 60).setScale(2, BigDecimal.ROUND_HALF_UP)
        def slackmsg = "[${env.JOB_NAME.replaceAll('%2F', '/')}-${env.BUILD_NUMBER}] `${currentBuild.result}`" + "\n" +
                "Elapsed Time: `${DURATION}` Mins" + "\n" +
                "tidb-operator Branch: `${BUILD_BRANCH}`, Githash: `${GITHASH.take(7)}`" + "\n" +
                "Display URL:" + "\n" +
                "${env.RUN_DISPLAY_URL}"

        if (currentBuild.result != "SUCCESS") {
            slackSend channel: '#cloud_jenkins', color: 'danger', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
            return
        }

        slackmsg = "${slackmsg}" + "\n" +
                "tidb-operator Docker Image: `hub.pingcap.net/jenkins/tidb-operator:${GITHASH} deployed to staging`"

        slackSend channel: '#cloud_jenkins', color: 'good', teamDomain: 'pingcap', tokenCredentialId: 'slack-pingcap-token', message: "${slackmsg}"
    }
}

return this
