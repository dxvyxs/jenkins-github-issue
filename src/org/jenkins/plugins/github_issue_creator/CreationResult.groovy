package org.jenkins.plugins.github_issue_creator

class CreationResult implements Serializable {
    private static final long serialVersionUID = 1L

    CreationAction action
    String identityHash
    int issueNumber = 0
    String failReason = ''
}