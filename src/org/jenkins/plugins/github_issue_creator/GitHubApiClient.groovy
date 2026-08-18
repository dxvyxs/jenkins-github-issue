package org.jenkins.plugins.github_issue_creator

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * HTTP client wrapper for GitHub API calls.
 * Single point of token usage — ensures the token never leaks via exceptions or logging.
 * All calls are synchronous and serialized (no concurrency from this component).
 */
class GitHubApiClient implements Serializable {
    private static final long serialVersionUID = 1L

    private final String apiBaseUrl
    private final SecureToken token
    private final Closure<Void> logger

    /** For testing: injectable HTTP executor */
    private final Closure<ApiResponse> httpExecutor

    GitHubApiClient(String apiBaseUrl, SecureToken token,
                    Closure<Void> logger = null, Closure<ApiResponse> httpExecutor = null) {
        this.apiBaseUrl = apiBaseUrl?.endsWith('/') ? apiBaseUrl[0..-2] : apiBaseUrl
        this.token = token
        this.logger = logger ?: { String msg -> println("[GitHubApiClient] ${msg}") }
        this.httpExecutor = httpExecutor
    }

    /**
     * Search issues in a repository by query string.
     * Uses GET /search/issues — not a mutating call.
     */
    ApiResponse searchIssues(String repo, String query) {
        String encodedQuery = "repo:${repo} ${query}".replaceAll(' ', '+')
        String url = "${apiBaseUrl}/search/issues?q=${URLEncoder.encode("repo:${repo} ${query}", 'UTF-8')}&per_page=10"
        return executeRequest('GET', url, null)
    }

    /**
     * Create a new issue.
     * Uses POST /repos/{owner}/{repo}/issues — mutating call.
     */
    ApiResponse createIssue(String repo, String title, String body, List<String> labels, List<String> assignees) {
        String url = "${apiBaseUrl}/repos/${repo}/issues"
        Map payload = [
            title: title,
            body: body,
            labels: labels ?: [],
            assignees: assignees ?: []
        ]
        return executeRequest('POST', url, JsonOutput.toJson(payload))
    }

    /**
     * Add a comment to an existing issue.
     * Uses POST /repos/{owner}/{repo}/issues/{number}/comments — mutating call.
     */
    ApiResponse addComment(String repo, int issueNumber, String body) {
        String url = "${apiBaseUrl}/repos/${repo}/issues/${issueNumber}/comments"
        Map payload = [body: body]
        return executeRequest('POST', url, JsonOutput.toJson(payload))
    }

    /**
     * Reopen a closed issue.
     * Uses PATCH /repos/{owner}/{repo}/issues/{number} — mutating call.
     */
    ApiResponse reopenIssue(String repo, int issueNumber) {
        String url = "${apiBaseUrl}/repos/${repo}/issues/${issueNumber}"
        Map payload = [state: 'open']
        return executeRequest('PATCH', url, JsonOutput.toJson(payload))
    }

    /**
     * Explicit rate-limit check.
     * Uses GET /rate_limit — not a mutating call.
     */
    ApiResponse getRateLimit() {
        String url = "${apiBaseUrl}/rate_limit"
        return executeRequest('GET', url, null)
    }

    /**
     * Execute an HTTP request with token-safe error handling.
     * This is the ONLY method that touches the token.
     */
    private ApiResponse executeRequest(String method, String url, String jsonBody) {
        logger.call("${method} ${sanitizeUrl(url)}")

        // If a test httpExecutor is injected, use it
        if (httpExecutor != null) {
            return httpExecutor.call(method, url, jsonBody)
        }

        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection()
            conn.setRequestMethod(method == 'PATCH' ? 'POST' : method)
            if (method == 'PATCH') {
                conn.setRequestProperty('X-HTTP-Method-Override', 'PATCH')
            }
            conn.setRequestProperty('Authorization', token.getHeaderValue())
            conn.setRequestProperty('Accept', 'application/vnd.github.v3+json')
            conn.setRequestProperty('Content-Type', 'application/json')
            conn.setRequestProperty('User-Agent', 'Jenkins-GitHub-Issue-Creator/1.0')
            conn.setConnectTimeout(30_000)
            conn.setReadTimeout(30_000)

            if (jsonBody) {
                conn.setDoOutput(true)
                conn.outputStream.withWriter('UTF-8') { writer ->
                    writer.write(jsonBody)
                }
            }

            int statusCode = conn.responseCode
            String responseBody
            try {
                responseBody = (statusCode >= 400 ? conn.errorStream : conn.inputStream)?.text ?: ''
            } catch (Exception e) {
                responseBody = ''
            }

            // Extract rate-limit headers
            Map<String, String> headers = [:]
            ['X-RateLimit-Remaining', 'X-RateLimit-Reset', 'X-RateLimit-Limit',
             'Retry-After', 'X-RateLimit-Resource'].each { headerName ->
                String value = conn.getHeaderField(headerName)
                if (value) headers[headerName] = value
            }

            logger.call("Response: ${statusCode}, Remaining: ${headers['X-RateLimit-Remaining'] ?: 'unknown'}")

            return new ApiResponse(
                statusCode: statusCode,
                body: responseBody,
                headers: headers
            )

        } catch (Exception e) {
            // CRITICAL: Strip any token information from the exception
            String safeMessage = sanitizeException(e)
            throw new RuntimeException("GitHub API call failed: ${safeMessage}", null)
        }
    }

    /**
     * Remove query parameters that might contain sensitive data from URLs for logging.
     */
    private static String sanitizeUrl(String url) {
        // Keep the path but truncate very long query strings
        int queryIdx = url.indexOf('?')
        if (queryIdx > 0 && url.length() > queryIdx + 80) {
            return url.substring(0, queryIdx + 80) + '...'
        }
        return url
    }

    /**
     * Remove any potential token leaks from exception messages.
     */
    private String sanitizeException(Exception e) {
        String msg = e.message ?: e.getClass().simpleName
        // Remove anything that looks like a Bearer token
        return msg
            .replaceAll(/(?i)bearer\s+\S+/, 'Bearer ***REDACTED***')
            .replaceAll(/ghp_[A-Za-z0-9_]+/, '***REDACTED***')
            .replaceAll(/ghs_[A-Za-z0-9_]+/, '***REDACTED***')
            .replaceAll(/github_pat_[A-Za-z0-9_]+/, '***REDACTED***')
    }
}
