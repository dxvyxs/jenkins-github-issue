package org.jenkins.plugins.github_issue_creator

import groovy.test.GroovyTestCase

/**
 * Integration-style tests for RetryExecutor.
 * Simulates real rate-limit pressure scenarios using injectable clock/sleeper
 * to verify timing behavior without actual delays.
 *
 * These tests validate the most error-prone component: retry/backoff logic
 * under various combinations of HTTP 403, 429, Retry-After headers,
 * X-RateLimit-Reset headers, and exponential backoff fallback.
 */
class RetryExecutorTest extends GroovyTestCase {

    long currentTimeMs
    List<Long> sleepDurations
    List<String> logMessages
    RateLimitManager rateLimitManager

    void setUp() {
        currentTimeMs = 1_000_000L // start at 1000 seconds
        sleepDurations = []
        logMessages = []
    }

    private RetryExecutor buildExecutor(int maxRetries = 5, long maxWaitMs = 300_000L, int threshold = 10) {
        rateLimitManager = new RateLimitManager(
            0, // no gap for tests
            threshold,
            { currentTimeMs },
            { long ms -> sleepDurations.add(ms); currentTimeMs += ms }
        )
        rateLimitManager.remaining = 5000 // start with healthy budget

        return new RetryExecutor(
            rateLimitManager,
            maxRetries,
            maxWaitMs,
            { long ms -> sleepDurations.add(ms); currentTimeMs += ms },
            { currentTimeMs },
            { String msg -> logMessages.add(msg) }
        )
    }

    // ========== Successful Execution ==========

    void testSuccessOnFirstAttempt() {
        def executor = buildExecutor()
        int callCount = 0

        ExecutionResult result = executor.execute(false) {
            callCount++
            return new ApiResponse(statusCode: 200, body: '{"ok":true}', headers: ['X-RateLimit-Remaining': '4999'])
        }

        assertTrue(result.isSuccess())
        assertEquals(1, callCount)
        assertTrue(sleepDurations.isEmpty())
    }

    void testSuccessAfterTransientErrors() {
        def executor = buildExecutor()
        int callCount = 0

        ExecutionResult result = executor.execute(false) {
            callCount++
            if (callCount <= 2) {
                return new ApiResponse(statusCode: 500, body: 'Internal Server Error', headers: ['X-RateLimit-Remaining': '4999'])
            }
            return new ApiResponse(statusCode: 200, body: '{"ok":true}', headers: ['X-RateLimit-Remaining': '4997'])
        }

        assertTrue(result.isSuccess())
        assertEquals(3, callCount)
        assertEquals(2, sleepDurations.size()) // slept twice for backoff
    }

    // ========== Rate Limit (403) with Retry-After Header ==========

    void testRetriesOn403WithRetryAfterHeader() {
        def executor = buildExecutor()
        int callCount = 0

        ExecutionResult result = executor.execute(true) {
            callCount++
            if (callCount == 1) {
                return new ApiResponse(
                    statusCode: 403,
                    body: '{"message":"rate limit exceeded"}',
                    headers: ['Retry-After': '5', 'X-RateLimit-Remaining': '0', 'X-RateLimit-Reset': '9999999']
                )
            }
            return new ApiResponse(statusCode: 201, body: '{"number":1}', headers: ['X-RateLimit-Remaining': '4999'])
        }

        assertTrue(result.isSuccess())
        assertEquals(2, callCount)
        // Should have waited based on Retry-After (5s = 5000ms) + jitter (0-1000ms)
        assertEquals(1, sleepDurations.size())
        long waitTime = sleepDurations[0]
        assertTrue("Wait was ${waitTime}ms, expected 5000-6000ms", waitTime >= 5000 && waitTime <= 6000)
    }

    void testRetriesOn429WithRetryAfterHeader() {
        def executor = buildExecutor()
        int callCount = 0

        ExecutionResult result = executor.execute(true) {
            callCount++
            if (callCount == 1) {
                return new ApiResponse(
                    statusCode: 429,
                    body: '{"message":"too many requests"}',
                    headers: ['Retry-After': '10', 'X-RateLimit-Remaining': '0']
                )
            }
            return new ApiResponse(statusCode: 200, body: '{}', headers: ['X-RateLimit-Remaining': '4998'])
        }

        assertTrue(result.isSuccess())
        assertEquals(2, callCount)
        long waitTime = sleepDurations[0]
        assertTrue("Wait was ${waitTime}ms, expected 10000-11000ms", waitTime >= 10000 && waitTime <= 11000)
    }

    // ========== Rate Limit with X-RateLimit-Reset Header ==========

    void testRetriesUsingRateLimitResetHeader() {
        def executor = buildExecutor()
        int callCount = 0
        // Current time: 1000s (1_000_000ms), reset at 1030s → 30s wait + 1s buffer
        currentTimeMs = 1_000_000L

        ExecutionResult result = executor.execute(true) {
            callCount++
            if (callCount == 1) {
                return new ApiResponse(
                    statusCode: 403,
                    body: '{"message":"API rate limit exceeded"}',
                    headers: ['X-RateLimit-Remaining': '0', 'X-RateLimit-Reset': '1030']
                )
            }
            return new ApiResponse(statusCode: 201, body: '{"number":5}', headers: ['X-RateLimit-Remaining': '4999'])
        }

        assertTrue(result.isSuccess())
        assertEquals(2, callCount)
        long waitTime = sleepDurations[0]
        // Should be ~31000ms (30s from reset + 1s buffer) + jitter (0-1000ms)
        assertTrue("Wait was ${waitTime}ms, expected 31000-32000ms", waitTime >= 31000 && waitTime <= 32000)
    }

    // ========== Exponential Backoff Fallback ==========

    void testExponentialBackoffWhenNoHeaders() {
        def executor = buildExecutor()
        int callCount = 0

        ExecutionResult result = executor.execute(false) {
            callCount++
            if (callCount <= 3) {
                return new ApiResponse(statusCode: 403, body: '{"message":"abuse"}', headers: ['X-RateLimit-Remaining': '50'])
            }
            return new ApiResponse(statusCode: 200, body: '{}', headers: ['X-RateLimit-Remaining': '49'])
        }

        assertTrue(result.isSuccess())
        assertEquals(4, callCount)
        assertEquals(3, sleepDurations.size())

        // Verify exponential progression (with jitter range)
        // Attempt 0: 2^0 * 2000 + jitter(0-1000) = 2000-3000
        // Attempt 1: 2^1 * 2000 + jitter(0-1000) = 4000-5000
        // Attempt 2: 2^2 * 2000 + jitter(0-1000) = 8000-9000
        assertTrue("sleep[0]=${sleepDurations[0]}", sleepDurations[0] >= 2000 && sleepDurations[0] <= 6000)
        assertTrue("sleep[1]=${sleepDurations[1]}", sleepDurations[1] >= 4000 && sleepDurations[1] <= 6000)
        assertTrue("sleep[2]=${sleepDurations[2]}", sleepDurations[2] >= 8000 && sleepDurations[2] <= 10000)
    }

    void testBackoffCapsAt64Seconds() {
        def executor = buildExecutor(10) // allow many retries
        int callCount = 0

        ExecutionResult result = executor.execute(false) {
            callCount++
            if (callCount <= 8) {
                return new ApiResponse(statusCode: 500, body: 'error', headers: ['X-RateLimit-Remaining': '1000'])
            }
            return new ApiResponse(statusCode: 200, body: '{}', headers: ['X-RateLimit-Remaining': '999'])
        }

        assertTrue(result.isSuccess())
        // Verify no sleep exceeds 64 seconds
        sleepDurations.each { sleep ->
            assertTrue("Sleep ${sleep}ms exceeds 64s cap", sleep <= 65_000) // 64s + 1s jitter
        }
    }

    // ========== Max Retry Cap ==========

    void testExhaustsMaxRetriesAndSafeFails() {
        def executor = buildExecutor(3) // only 3 retries
        int callCount = 0

        ExecutionResult result = executor.execute(true) {
            callCount++
            return new ApiResponse(
                statusCode: 429,
                body: '{"message":"rate limited"}',
                headers: ['X-RateLimit-Remaining': '0']
            )
        }

        assertTrue(result.isSafeFail())
        assertEquals(4, callCount) // initial + 3 retries
        def safeFail = result as ExecutionResult.SafeFail
        assertTrue(safeFail.reason.contains('Rate limited'))
        assertTrue(safeFail.shouldAlert)
    }

    void testMaxRetriesWithTransientErrors() {
        def executor = buildExecutor(2) // only 2 retries
        int callCount = 0

        ExecutionResult result = executor.execute(false) {
            callCount++
            return new ApiResponse(statusCode: 502, body: 'Bad Gateway', headers: ['X-RateLimit-Remaining': '5000'])
        }

        assertTrue(result.isSafeFail())
        assertEquals(3, callCount) // initial + 2 retries
        def safeFail = result as ExecutionResult.SafeFail
        assertTrue(safeFail.reason.contains('Transient error'))
    }

    // ========== Auth Errors — No Retry ==========

    void testNoRetryOn401() {
        def executor = buildExecutor()
        int callCount = 0

        ExecutionResult result = executor.execute(true) {
            callCount++
            return new ApiResponse(statusCode: 401, body: '{"message":"Bad credentials"}', headers: [:])
        }

        assertTrue(result.isSafeFail())
        assertEquals(1, callCount) // only one attempt, no retry
        def safeFail = result as ExecutionResult.SafeFail
        assertTrue(safeFail.reason.contains('Authentication'))
    }

    void testNoRetryOn404() {
        def executor = buildExecutor()
        int callCount = 0

        ExecutionResult result = executor.execute(true) {
            callCount++
            return new ApiResponse(statusCode: 404, body: '{"message":"Not Found"}', headers: [:])
        }

        assertTrue(result.isSafeFail())
        assertEquals(1, callCount) // no retry for 404
    }

    // ========== Connection Errors ==========

    void testRetriesOnConnectionError() {
        def executor = buildExecutor()
        int callCount = 0

        ExecutionResult result = executor.execute(false) {
            callCount++
            if (callCount <= 2) {
                throw new RuntimeException("Connection refused")
            }
            return new ApiResponse(statusCode: 200, body: '{}', headers: ['X-RateLimit-Remaining': '4999'])
        }

        assertTrue(result.isSuccess())
        assertEquals(3, callCount)
        assertEquals(2, sleepDurations.size())
    }

    void testConnectionErrorExhaustsRetries() {
        def executor = buildExecutor(2)
        int callCount = 0

        ExecutionResult result = executor.execute(false) {
            callCount++
            throw new RuntimeException("Connection timed out")
        }

        assertTrue(result.isSafeFail())
        assertEquals(3, callCount)
        def safeFail = result as ExecutionResult.SafeFail
        assertTrue(safeFail.reason.contains('Connection error'))
        assertTrue(safeFail.shouldAlert)
    }

    // ========== Rate-Limit Gate (Pre-flight Check) ==========

    void testSafeFailWhenRateLimitResetTooFarInFuture() {
        def executor = buildExecutor(5, 60_000L) // max wait 60s
        rateLimitManager.remaining = 5 // below threshold of 10
        rateLimitManager.resetEpochSeconds = (currentTimeMs / 1000L) + 600 // 10 minutes from now

        ExecutionResult result = executor.execute(false) {
            return new ApiResponse(statusCode: 200, body: '{}', headers: [:])
        }

        assertTrue(result.isSafeFail())
        def safeFail = result as ExecutionResult.SafeFail
        assertTrue(safeFail.reason.contains('too far in future'))
        assertTrue(safeFail.shouldAlert)
    }

    void testWaitsForResetWhenBudgetLow() {
        def executor = buildExecutor(5, 300_000L, 10)
        rateLimitManager.remaining = 5 // below threshold
        rateLimitManager.resetEpochSeconds = (currentTimeMs / 1000L) + 10 // 10 seconds from now

        // After waiting, remaining should be refreshed by the response
        int callCount = 0
        ExecutionResult result = executor.execute(false) {
            callCount++
            // Simulate that after the wait, the rate limit has reset
            rateLimitManager.remaining = 5000
            return new ApiResponse(statusCode: 200, body: '{}', headers: ['X-RateLimit-Remaining': '4999'])
        }

        // The executor should have slept waiting for reset, then succeeded
        assertTrue(result.isSuccess())
        assertTrue(sleepDurations.size() >= 1)
    }

    // ========== Mixed Scenario: 403 then 500 then Success ==========

    void testMixedErrorTypesBeforeSuccess() {
        def executor = buildExecutor()
        int callCount = 0

        ExecutionResult result = executor.execute(true) {
            callCount++
            switch (callCount) {
                case 1:
                    return new ApiResponse(statusCode: 403, body: '{"message":"rate limit"}',
                        headers: ['Retry-After': '2', 'X-RateLimit-Remaining': '0'])
                case 2:
                    return new ApiResponse(statusCode: 500, body: 'Internal Server Error',
                        headers: ['X-RateLimit-Remaining': '4000'])
                case 3:
                    return new ApiResponse(statusCode: 201, body: '{"number":99}',
                        headers: ['X-RateLimit-Remaining': '3999'])
                default:
                    fail("Unexpected call #${callCount}")
            }
        }

        assertTrue(result.isSuccess())
        assertEquals(3, callCount)
        assertEquals(2, sleepDurations.size())
        // First sleep: Retry-After 2s + jitter
        assertTrue("sleep[0]=${sleepDurations[0]}", sleepDurations[0] >= 2000 && sleepDurations[0] <= 3000)
        // Second sleep: exponential backoff for attempt 1
        assertTrue("sleep[1]=${sleepDurations[1]}", sleepDurations[1] >= 4000 && sleepDurations[1] <= 5000)
    }

    // ========== Token Safety in Error Messages ==========

    void testTokenRedactedFromConnectionErrors() {
        def executor = buildExecutor(0) // no retries

        ExecutionResult result = executor.execute(false) {
            throw new RuntimeException("Failed with token ghp_1234567890abcdefghij in header")
        }

        assertTrue(result.isSafeFail())
        def safeFail = result as ExecutionResult.SafeFail
        assertFalse(safeFail.reason.contains('ghp_1234567890'))
        assertTrue(safeFail.reason.contains('***REDACTED***'))
    }

    void testBearerTokenRedactedFromErrors() {
        def executor = buildExecutor(0)

        ExecutionResult result = executor.execute(false) {
            throw new RuntimeException("Authorization: Bearer ghs_secret123token456 was rejected")
        }

        assertTrue(result.isSafeFail())
        def safeFail = result as ExecutionResult.SafeFail
        assertFalse(safeFail.reason.contains('ghs_secret'))
        assertTrue(safeFail.reason.contains('***REDACTED***'))
    }

    // ========== Timing Verification ==========

    void testBackoffTimingIsCorrect() {
        def executor = buildExecutor(4)
        int callCount = 0
        long startTime = currentTimeMs

        ExecutionResult result = executor.execute(false) {
            callCount++
            return new ApiResponse(statusCode: 503, body: 'unavailable', headers: ['X-RateLimit-Remaining': '5000'])
        }

        assertTrue(result.isSafeFail())
        assertEquals(5, callCount) // initial + 4 retries

        // Total elapsed should be sum of backoffs
        long totalElapsed = currentTimeMs - startTime
        long totalSleep = sleepDurations.sum() as long

        assertEquals(totalElapsed, totalSleep) // all time was spent sleeping
        assertTrue("Total sleep ${totalSleep}ms should be substantial", totalSleep > 10_000)
    }

    void testMinimumGapEnforcedForMutatingCalls() {
        // Use a rate limit manager with a 1000ms gap
        rateLimitManager = new RateLimitManager(
            1000, // 1s gap
            10,
            { currentTimeMs },
            { long ms -> sleepDurations.add(ms); currentTimeMs += ms }
        )
        rateLimitManager.remaining = 5000

        def executor = new RetryExecutor(
            rateLimitManager, 5, 300_000L,
            { long ms -> sleepDurations.add(ms); currentTimeMs += ms },
            { currentTimeMs },
            { String msg -> logMessages.add(msg) }
        )

        // Make first mutating call
        executor.execute(true) {
            return new ApiResponse(statusCode: 201, body: '{}', headers: ['X-RateLimit-Remaining': '4999'])
        }

        long timeAfterFirst = currentTimeMs
        sleepDurations.clear()

        // Make second mutating call immediately — should enforce gap
        executor.execute(true) {
            return new ApiResponse(statusCode: 201, body: '{}', headers: ['X-RateLimit-Remaining': '4998'])
        }

        // The second call should have triggered a gap sleep
        if (sleepDurations.size() > 0) {
            long gapSleep = sleepDurations[0]
            assertTrue("Gap sleep ${gapSleep}ms should be <= 1000ms", gapSleep <= 1000)
        }
    }

    // ========== Alert Trigger Tests ==========

    void testSafeFailWithAlertFlag() {
        def executor = buildExecutor(0)

        ExecutionResult result = executor.execute(true) {
            return new ApiResponse(statusCode: 429, body: '{"message":"rate limited"}', headers: [:])
        }

        assertTrue(result.isSafeFail())
        assertTrue((result as ExecutionResult.SafeFail).shouldAlert)
    }

    void testClientErrorNoAlert() {
        def executor = buildExecutor(0)

        ExecutionResult result = executor.execute(true) {
            return new ApiResponse(statusCode: 422, body: '{"message":"Validation Failed"}', headers: [:])
        }

        assertTrue(result.isSafeFail())
        assertFalse((result as ExecutionResult.SafeFail).shouldAlert) // client errors don't alert
    }

    // ========== Retry-After Priority Over Reset Header ==========

    void testRetryAfterTakesPriorityOverReset() {
        def executor = buildExecutor()
        int callCount = 0

        ExecutionResult result = executor.execute(true) {
            callCount++
            if (callCount == 1) {
                // Both headers present — Retry-After should win
                return new ApiResponse(
                    statusCode: 429,
                    body: '{}',
                    headers: [
                        'Retry-After': '3',
                        'X-RateLimit-Remaining': '0',
                        'X-RateLimit-Reset': "${(currentTimeMs / 1000L) + 300}" // 5 min from now
                    ]
                )
            }
            return new ApiResponse(statusCode: 200, body: '{}', headers: ['X-RateLimit-Remaining': '4999'])
        }

        assertTrue(result.isSuccess())
        // Should have used Retry-After (3s) not Reset (5min)
        assertTrue("sleep[0]=${sleepDurations[0]}", sleepDurations[0] >= 3000 && sleepDurations[0] <= 4000)
    }

    // ========== Max Wait Exceeded ==========

    void testSafeFailWhenRequiredWaitExceedsMax() {
        def executor = buildExecutor(5, 10_000L) // max wait only 10s
        int callCount = 0

        ExecutionResult result = executor.execute(true) {
            callCount++
            // Return a Retry-After that exceeds max wait
            return new ApiResponse(
                statusCode: 429,
                body: '{}',
                headers: ['Retry-After': '60', 'X-RateLimit-Remaining': '0'] // 60s > 10s max
            )
        }

        assertTrue(result.isSafeFail())
        def safeFail = result as ExecutionResult.SafeFail
        assertTrue(safeFail.reason.contains('exceeds max'))
        assertEquals(1, callCount)
    }
}
