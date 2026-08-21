package org.jenkins.plugins.github_issue_creator

class FailureContext implements Serializable {
    private static final long serialVersionUID = 1L

    String jobName
    String stageName
    int buildNumber
    String buildUrl

    String toolName
    Map sarifResult
}