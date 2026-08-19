@Library('github-issue-creator') _

pipeline {
    agent any
    
    stages {
        stage('Build') {
            steps {
                sh 'exit 1'  // Force failure to test the notification
            }
        }
    }
    post {
        failure {
            script {
                notifyGitHubIssue(
                    githubRepo: 'dxvyxs/slsa-demo',
                    credentialId: 'github-issue-token',
                    labels: ['ci-failure', 'automated'],
                    stageName: 'Build'
                    rateLimitThreshold: 10
                )
            }
        }
    }
}
