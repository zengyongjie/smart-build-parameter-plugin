pipeline {
  agent any
  stages {
    stage('Build') {
      steps {
        sh 'mvn -B -ntp verify'
      }
      post {
        always {
          junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
        }
      }
    }
  }
}
