package org.jenkins.plugins.github_issue_creator

import groovy.test.GroovyTestCase
import groovy.json.JsonOutput

/**
 * Unit tests for GitHubIssueCreator orchestrator.
 * Verifies the dedup decision logic:
 * - No existing issue → create new
 * - Open issue with matching marker → comment
 * - Closed issue with matching marker → reopen + comment
 * - Dedup cache prevents redundant API calls within same build
 * - Safe-fail on API errors
 */
class GitHubIssueCreatorTest extends GroovyTestCase {

    Config config
    List<String> apiCalls
    List<String> logMessages
    Map<String, ApiResponse> mockResponses

    void setUp() {
        config = new Config(
            githubRepo: 'test-owner/test-repo',
            credentialId: 'test-cred',
            labels: ['ci-failure'],
            assignees: [],
            maxRetries: 3,
            minCallGapMs: 0, // disable gap for tests
            rateLimitThreshold: 10
        )
        apiCalls = []
        logMessages = []
        mockResponses = [:]
    }

    // ========== Dedup Decision Tests ==========

    void testCreatesNewIssueWhenNoExistingMatch() {
        // Mock: search returns empty results, create returns success
        GitHubIssueCreator creator = buildCreator([
            search: searchResponse([]),
            create: createResponse(42)
        ])

        FailureContext ctx = buildContext('job', 'Build', 'Error: compile failed')
        CreationResult result = creator.createOrUpdateIssue(ctx)

        assertEquals(CreationAction.CREATED, result.action)
        assertEquals(42, result.issueNumber)
        assertTrue(apiCalls.any { it.startsWith('SEARCH:') })
        assertTrue(apiCalls.any { it.startsWith('CREATE:') })
    }

    void testCommentsOnExistingOpenIssue() {
        // Compute the expected identity to embed in mock issue
        def identity = FailureIdentityEngine.compute('job', 'Build', 'Error: compile failed')

        // Mock: search returns an open issue with matching marker
        GitHubIssueCreator creator = buildCreator([
            search: searchResponse([
                [number: 10, state: 'open', body: "Some body\n${identity.marker}"]
            ]),
            comment: commentResponse()
        ])

        FailureContext ctx = buildContext('job', 'Build', 'Error: compile failed')
        CreationResult result = creator.createOrUpdateIssue(ctx)

        assertEquals(CreationAction.COMMENTED, result.action)
        assertEquals(10, result.issueNumber)
        assertTrue(apiCalls.any { it.startsWith('SEARCH:') })
        assertTrue(apiCalls.any { it.startsWith('COMMENT:') })
        assertFalse(apiCalls.any { it.startsWith('CREATE:') })
    }

    void testReopensClosedIssue() {
        def identity = FailureIdentityEngine.compute('job', 'Build', 'Error: compile failed')

        GitHubIssueCreator creator = buildCreator([
            search: searchResponse([
                [number: 7, state: 'closed', body: "Old body\n${identity.marker}"]
            ]),
            reopen: reopenResponse(7),
            comment: commentResponse()
        ])

        FailureContext ctx = buildContext('job', 'Build', 'Error: compile failed')
        CreationResult result = creator.createOrUpdateIssue(ctx)

        assertEquals(CreationAction.REOPENED, result.action)
        assertEquals(7, result.issueNumber)
        assertTrue(apiCalls.any { it.startsWith('REOPEN:') })
        assertTrue(apiCalls.any { it.startsWith('COMMENT:') })
    }

    void testDedupCachePreventsRedundantCalls() {
        // First call creates issue
        GitHubIssueCreator creator = buildCreator([
            search: searchResponse([]),
            create: createResponse(55)
        ])

        FailureContext ctx = buildContext('job', 'Build', 'Error: compile failed')

        // First call
        CreationResult result1 = creator.createOrUpdateIssue(ctx)
        assertEquals(CreationAction.CREATED, result1.action)

        // Clear API calls tracking
        apiCalls.clear()

        // Second call with same failure — should hit cache, no API calls
        CreationResult result2 = creator.createOrUpdateIssue(ctx)
        assertEquals(CreationAction.SKIPPED_CACHED, result2.action)
        assertEquals(55, result2.issueNumber)
        assertTrue(apiCalls.isEmpty()) // No API calls made
    }

    void testDedupCacheDoesNotMatchDifferentFailures() {
        GitHubIssueCreator creator = buildCreator([
            search: searchResponse([]),
            create: createResponse(60)
        ])

        FailureContext ctx1 = buildContext('job', 'Build', 'Error: compile failed')
        FailureContext ctx2 = buildContext('job', 'Build', 'Error: out of memory')

        creator.createOrUpdateIssue(ctx1)
        apiCalls.clear()

        // Different error — should NOT hit cache
        CreationResult result2 = creator.createOrUpdateIssue(ctx2)
        assertNotEquals(CreationAction.SKIPPED_CACHED, result2.action)
        assertFalse(apiCalls.isEmpty())
    }

    // ========== Marker Matching Tests ==========

    void testIgnoresIssuesWithDifferentMarker() {
        // Search returns an issue, but with a different identity marker
        String otherMarker = '<!-- jenkins-issue-id:0000000000000000 -->'

        GitHubIssueCreator creator = buildCreator([
            search: searchResponse([
                [number: 99, state: 'open', body: "Body\n${otherMarker}"]
            ]),
            create: createResponse(100)
        ])

        FailureContext ctx = buildContext('job', 'Build', 'Error: specific error')
        CreationResult result = creator.createOrUpdateIssue(ctx)

        // Should create a new issue because marker doesn't match
        assertEquals(CreationAction.CREATED, result.action)
        assertEquals(100, result.issueNumber)
    }

    void testIgnoresIssuesWithNoMarker() {
        GitHubIssueCreator creator = buildCreator([
            search: searchResponse([
                [number: 5, state: 'open', body: 'A manually created issue with no marker']
            ]),
            create: createResponse(6)
        ])

        FailureContext ctx = buildContext('job', 'Build', 'Error: something')
        CreationResult result = creator.createOrUpdateIssue(ctx)

        assertEquals(CreationAction.CREATED, result.action)
    }

    // ========== Safe-Fail Tests ==========

    void testSafeFailOnSearchError() {
        GitHubIssueCreator creator = buildCreator([
            search: errorResponse(500, 'Internal Server Error')
        ])

        FailureContext ctx = buildContext('job', 'Build', 'Error: something')
        CreationResult result = creator.createOrUpdateIssue(ctx)

        assertEquals(CreationAction.SAFE_FAIL, result.action)
        assertNotNull(result.failReason)
    }

    void testSafeFailOnCreateError() {
        GitHubIssueCreator creator = buildCreator([
            search: searchResponse([]),
            create: errorResponse(401, 'Bad credentials')
        ])

        FailureContext ctx = buildContext('job', 'Build', 'Error: something')
        CreationResult result = creator.createOrUpdateIssue(ctx)

        assertEquals(CreationAction.SAFE_FAIL, result.action)
        assertTrue(result.failReason.contains('Authentication'))
    }

    // ========== Config Validation Tests ==========

    void testRejectsInvalidConfig() {
        config.githubRepo = ''
        GitHubIssueCreator creator = buildCreator([search: searchResponse([])])

        FailureContext ctx = buildContext('job', 'Build', 'Error')

        shouldFail(IllegalArgumentException) {
            creator.createOrUpdateIssue(ctx)
        }
    }

    // ========== Helper Methods ==========

    private GitHubIssueCreator buildCreator(Map<String, ApiResponse> responses) {
        this.mockResponses = responses
        int searchCallCount = 0
        int createCallCount = 0

        // Build a mock API client
        GitHubApiClient apiClient = new GitHubApiClient(
            'https://api.github.com',
            new SecureToken('test-token-for-unit-tests'),
            { String msg -> logMessages.add(msg) },
            // Mock HTTP executor
            { String method, String url, String body ->
                if (method == 'GET' && url.contains('/search/')) {
                    apiCalls.add("SEARCH: ${url}")
                    return responses.search
                } else if (method == 'POST' && url.contains('/comments')) {
                    apiCalls.add("COMMENT: ${url}")
                    return responses.comment ?: commentResponse()
                } else if (method == 'POST' && url.contains('/issues') && !url.contains('/comments')) {
                    apiCalls.add("CREATE: ${url}")
                    return responses.create
                } else if (method == 'PATCH' || (method == 'POST' && body?.contains('"state"'))) {
                    apiCalls.add("REOPEN: ${url}")
                    return responses.reopen ?: reopenResponse(0)
                }
                return new ApiResponse(statusCode: 404, body: '{}', headers: [:])
            }
        )

        RateLimitManager rateLimitManager = new RateLimitManager(0, 10, { System.currentTimeMillis() }, { long ms -> })
        RetryExecutor retryExecutor = new RetryExecutor(
            rateLimitManager, config.maxRetries, config.maxWaitMs ?: 300_000L,
            { long ms -> }, // no-op sleeper
            { System.currentTimeMillis() },
            { String msg -> logMessages.add(msg) }
        )
        AlertNotifier alertNotifier = new AlertNotifier(null, { String msg -> logMessages.add(msg) })

        return new GitHubIssueCreator(
            config, apiClient, rateLimitManager, retryExecutor, alertNotifier,
            { String msg -> logMessages.add(msg) }
        )
    }

    private FailureContext buildContext(String jobName, String stageName, String log) {
        return new FailureContext(
            jobName: jobName,
            stageName: stageName,
            failureLog: log,
            buildNumber: 42,
            buildUrl: 'http://jenkins/job/42'
        )
    }

    private static ApiResponse searchResponse(List<Map> items) {
        String body = JsonOutput.toJson([total_count: items.size(), items: items])
        return new ApiResponse(
            statusCode: 200,
            body: body,
            headers: ['X-RateLimit-Remaining': '4999', 'X-RateLimit-Reset': '9999999999']
        )
    }

    private static ApiResponse createResponse(int issueNumber) {
        String body = JsonOutput.toJson([number: issueNumber, state: 'open', html_url: "https://github.com/test/issues/${issueNumber}"])
        return new ApiResponse(
            statusCode: 201,
            body: body,
            headers: ['X-RateLimit-Remaining': '4998', 'X-RateLimit-Reset': '9999999999']
        )
    }

    private static ApiResponse commentResponse() {
        String body = JsonOutput.toJson([id: 123, body: 'comment'])
        return new ApiResponse(
            statusCode: 201,
            body: body,
            headers: ['X-RateLimit-Remaining': '4997', 'X-RateLimit-Reset': '9999999999']
        )
    }

    private static ApiResponse reopenResponse(int issueNumber) {
        String body = JsonOutput.toJson([number: issueNumber, state: 'open'])
        return new ApiResponse(
            statusCode: 200,
            body: body,
            headers: ['X-RateLimit-Remaining': '4996', 'X-RateLimit-Reset': '9999999999']
        )
    }

    private static ApiResponse errorResponse(int statusCode, String message) {
        String body = JsonOutput.toJson([message: message])
        return new ApiResponse(
            statusCode: statusCode,
            body: body,
            headers: ['X-RateLimit-Remaining': '0', 'X-RateLimit-Reset': '9999999999']
        )
    }

    private static void assertNotEquals(Object expected, Object actual) {
        assertFalse("Expected values to differ but both were: ${expected}", expected == actual)
    }
}
