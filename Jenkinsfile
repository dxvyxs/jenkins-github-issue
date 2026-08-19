@Library('github-issue-creator') _

pipeline {
    agent any
    
    stages {
        stage('Build') {
            steps {
                sh 'echo "Build successful"'  
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
                    stageName: 'Build',
                    rateLimitThreshold: 10
                )
            }
        }
    }
}
