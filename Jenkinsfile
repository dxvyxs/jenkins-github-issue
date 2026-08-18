@Library('github-issue-creator') _

pipeline {
    agent any
    
    stages {
        stage('Build') {
            steps {
                echo 'Building...'
            }
        }
    }
    post {
        failure {
            script {
                notifyGithubIssue(
                    repo: 'your-org/your-repo',
                    credentialsId: 'github-token'
                )
            }
        }
    }
}
