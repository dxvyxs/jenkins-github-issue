package org.jenkins.plugins.github_issue_creator

class FailureIdentity implements Serializable {
    private static final long serialVersionUID = 1L

    String hash
    String marker
    String identityInput
}