package org.jenkins.plugins.github_issue_creator

import groovy.test.GroovyTestCase
import groovy.json.JsonSlurper

/**
 * Integration-style tests for GitHubApiClient.
 * Uses a mock HTTP executor to simulate GitHub API responses
 * and verify correct request construction, header handling, and error behavior.
 */
class GitHubApiClientTest extends GroovyTestCase {

    List<Map> requestLog
    List<String> logMessages

    void setUp() {
        requestLog = []
        logMessages = []
    }

    private GitHubApiClient buildClient(Closure<ApiResponse> httpExecutor) {
        return new GitHubApiClient(
            'https://api.github.com',
            new SecureToken('test-token-never-logged'),
            { String msg -> logMessages.add(msg) },
            httpExecutor
        )
    }

    // ========== Search Issues ==========

    void testSearchIssuesConstructsCorrectRequest() {
        def client = buildClient { String method, String url, String body ->
            requestLog.add([method: method, url: url, body: body])
            return new ApiResponse(
                statusCode: 200,
                body: '{"total_count":0,"items":[]}',
                headers: ['X-RateLimit-Remaining': '29']
            )
        }

        ApiResponse response = client.searchIssues('owner/repo', '<!-- jenkins-issue-id:abc123 --> in:body')

        assertEquals(1, requestLog.size())
        assertEquals('GET', requestLog[0].method)
        assertTrue(requestLog[0].url.contains('/search/issues'))
        assertTrue(requestLog[0].url.contains('owner%2Frepo'))
        assertNull(requestLog[0].body)
        assertEquals(200, response.statusCode)
    }

    void testSearchIssuesReturnsMatchingIssues() {
        def client = buildClient { String method, String url, String body ->
            return new ApiResponse(
                statusCode: 200,
                body: '{"total_count":1,"items":[{"number":42,"state":"open","body":"text <!-- jenkins-issue-id:abc123 --> more"}]}',
                headers: ['X-RateLimit-Remaining': '28']
            )
        }

        ApiResponse response = client.searchIssues('owner/repo', 'test query')
        def parsed = new JsonSlurper().parseText(response.body)

        assertEquals(1, parsed.total_count)
        assertEquals(42, parsed.items[0].number)
    }

    // ========== Create Issue ==========

    void testCreateIssueConstructsCorrectRequest() {
        def client = buildClient { String method, String url, String body ->
            requestLog.add([method: method, url: url, body: body])
            return new ApiResponse(
                statusCode: 201,
                body: '{"number":99,"state":"open","html_url":"https://github.com/owner/repo/issues/99"}',
                headers: ['X-RateLimit-Remaining': '4998']
            )
        }

        ApiResponse response = client.createIssue(
            'owner/repo',
            '[CI Failure] my-job — Build',
            'Issue body with marker',
            ['ci-failure', 'automated'],
            ['assignee1']
        )

        assertEquals(1, requestLog.size())
        assertEquals('POST', requestLog[0].method)
        assertTrue(requestLog[0].url.endsWith('/repos/owner/repo/issues'))

        def payload = new JsonSlurper().parseText(requestLog[0].body)
        assertEquals('[CI Failure] my-job — Build', payload.title)
        assertEquals('Issue body with marker', payload.body)
        assertEquals(['ci-failure', 'automated'], payload.labels)
        assertEquals(['assignee1'], payload.assignees)

        assertEquals(201, response.statusCode)
    }

    // ========== Add Comment ==========

    void testAddCommentConstructsCorrectRequest() {
        def client = buildClient { String method, String url, String body ->
            requestLog.add([method: method, url: url, body: body])
            return new ApiResponse(
                statusCode: 201,
                body: '{"id":12345}',
                headers: ['X-RateLimit-Remaining': '4997']
            )
        }

        client.addComment('owner/repo', 42, 'New failure detected')

        assertEquals('POST', requestLog[0].method)
        assertTrue(requestLog[0].url.endsWith('/repos/owner/repo/issues/42/comments'))

        def payload = new JsonSlurper().parseText(requestLog[0].body)
        assertEquals('New failure detected', payload.body)
    }

    // ========== Reopen Issue ==========

    void testReopenIssueConstructsCorrectRequest() {
        def client = buildClient { String method, String url, String body ->
            requestLog.add([method: method, url: url, body: body])
            return new ApiResponse(
                statusCode: 200,
                body: '{"number":7,"state":"open"}',
                headers: ['X-RateLimit-Remaining': '4996']
            )
        }

        client.reopenIssue('owner/repo', 7)

        assertEquals('PATCH', requestLog[0].method)
        assertTrue(requestLog[0].url.endsWith('/repos/owner/repo/issues/7'))

        def payload = new JsonSlurper().parseText(requestLog[0].body)
        assertEquals('open', payload.state)
    }

    // ========== Rate Limit Check ==========

    void testGetRateLimitEndpoint() {
        def client = buildClient { String method, String url, String body ->
            requestLog.add([method: method, url: url, body: body])
            return new ApiResponse(
                statusCode: 200,
                body: '{"rate":{"limit":5000,"remaining":4999,"reset":1700000000}}',
                headers: ['X-RateLimit-Remaining': '4999', 'X-RateLimit-Reset': '1700000000']
            )
        }

        ApiResponse response = client.getRateLimit()

        assertEquals('GET', requestLog[0].method)
        assertTrue(requestLog[0].url.endsWith('/rate_limit'))
        assertEquals(200, response.statusCode)
    }

    // ========== Response Header Extraction ==========

    void testResponseHeadersPreserved() {
        def client = buildClient { String method, String url, String body ->
            return new ApiResponse(
                statusCode: 200,
                body: '{}',
                headers: [
                    'X-RateLimit-Remaining': '100',
                    'X-RateLimit-Reset': '1700001000',
                    'X-RateLimit-Limit': '5000'
                ]
            )
        }

        ApiResponse response = client.searchIssues('owner/repo', 'query')

        assertEquals('100', response.headers['X-RateLimit-Remaining'])
        assertEquals('1700001000', response.headers['X-RateLimit-Reset'])
        assertEquals('5000', response.headers['X-RateLimit-Limit'])
    }

    // ========== GitHub Enterprise Support ==========

    void testGitHubEnterpriseBaseUrl() {
        def client = new GitHubApiClient(
            'https://github.mycompany.com/api/v3',
            new SecureToken('ghe-token'),
            { String msg -> logMessages.add(msg) },
            { String method, String url, String body ->
                requestLog.add([method: method, url: url, body: body])
                return new ApiResponse(statusCode: 200, body: '{"total_count":0,"items":[]}', headers: [:])
            }
        )

        client.searchIssues('internal/project', 'query')

        assertTrue(requestLog[0].url.startsWith('https://github.mycompany.com/api/v3/search/issues'))
    }

    void testTrailingSlashStrippedFromBaseUrl() {
        def client = new GitHubApiClient(
            'https://api.github.com/',
            new SecureToken('token'),
            { String msg -> logMessages.add(msg) },
            { String method, String url, String body ->
                requestLog.add([method: method, url: url, body: body])
                return new ApiResponse(statusCode: 200, body: '{}', headers: [:])
            }
        )

        client.getRateLimit()

        // Should not have double slash
        assertFalse(requestLog[0].url.contains('//rate_limit'))
    }

    // ========== Token Security ==========

    void testTokenNeverAppearsInLogs() {
        String secretToken = 'ghp_SuperSecretToken12345678901234'
        def client = new GitHubApiClient(
            'https://api.github.com',
            new SecureToken(secretToken),
            { String msg -> logMessages.add(msg) },
            { String method, String url, String body ->
                return new ApiResponse(statusCode: 200, body: '{}', headers: [:])
            }
        )

        client.searchIssues('owner/repo', 'query')
        client.createIssue('owner/repo', 'title', 'body', [], [])
        client.addComment('owner/repo', 1, 'comment')

        // Verify token never appears in any log message
        logMessages.each { msg ->
            assertFalse("Token found in log: ${msg}", msg.contains(secretToken))
            assertFalse("Token found in log: ${msg}", msg.contains('ghp_SuperSecret'))
        }
    }

    void testSecureTokenToStringIsRedacted() {
        def token = new SecureToken('ghp_realtoken123')

        assertEquals('***REDACTED***', token.toString())
        assertEquals('SecureToken[REDACTED]', token.inspect())
    }

    void testSecureTokenDestroyZerosMemory() {
        def token = new SecureToken('ghp_realtoken123')
        token.destroy()

        shouldFail(IllegalStateException) {
            token.getHeaderValue()
        }
        shouldFail(IllegalStateException) {
            token.getRawValue()
        }
    }

    // ========== Error Response Handling ==========

    void testErrorResponseParseable() {
        def client = buildClient { String method, String url, String body ->
            return new ApiResponse(
                statusCode: 422,
                body: '{"message":"Validation Failed","errors":[{"code":"invalid"}]}',
                headers: ['X-RateLimit-Remaining': '4999']
            )
        }

        ApiResponse response = client.createIssue('owner/repo', '', 'body', [], [])
        assertEquals(422, response.statusCode)

        def parsed = response.parsedBody()
        assertEquals('Validation Failed', parsed.message)
    }

    void testEmptyResponseBodyHandled() {
        def client = buildClient { String method, String url, String body ->
            return new ApiResponse(statusCode: 204, body: '', headers: [:])
        }

        ApiResponse response = client.getRateLimit()
        assertEquals(204, response.statusCode)
        def parsed = response.parsedBody()
        assertEquals([:], parsed)
    }
}
