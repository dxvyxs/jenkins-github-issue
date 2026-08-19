package org.jenkins.plugins.github_issue_creator

import groovy.json.JsonSlurper

class ApiResponse implements Serializable {
    private static final long serialVersionUID = 1L

    int statusCode
    String body
    Map<String, String> headers

    Object parsedBody() {
        if (!body) return null
        try {
            return new JsonSlurper().parseText(body)
        } catch (Exception e) {
            return null
        }
    }

    boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300
    }

    boolean isRateLimited() {
        return statusCode in [429, 403]
    }

    String getRateLimitReset() {
        return headers?.get('X-RateLimit-Reset') ?: headers?.get('Retry-After')
    }
}