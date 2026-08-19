package org.jenkins.plugins.github_issue_creator

/**
 * Executes actions with bounded retry logic.
 * Handles rate-limit errors (403/429), transient errors (5xx/connection),
 * and auth errors (401/404 — no retry).
 *
 * Retry strategy:
 * 1. Check Retry-After header
 * 2. Compute wait from X-RateLimit-Reset
 * 3. Exponential backoff with jitter
 * Hard cap: maxRetries attempts, then safe-fail.
 */
class RetryExecutor implements Serializable {
    private static final long serialVersionUID = 1L

    private final RateLimitManager rateLimitManager
    private final int maxRetries
    private final long maxWaitMs

    /** Injectable sleep function for testing */
    private final Closure<Void> sleeper

    /** Injectable clock for testing */
    private final Closure<Long> clock

    /** Logger (injectable for testing) */
    private final Closure<Void> logger

    RetryExecutor(RateLimitManager rateLimitManager, int maxRetries = 5, long maxWaitMs = 300_000L,
                  Closure<Void> sleeper = null, Closure<Long> clock = null, Closure<Void> logger = null) {
        this.rateLimitManager = rateLimitManager
        this.maxRetries = maxRetries
        this.maxWaitMs = maxWaitMs
        this.sleeper = sleeper ?: { long ms -> Thread.sleep(ms) }
        this.clock = clock ?: { System.currentTimeMillis() }
        this.logger = logger ?: { String msg -> println("[GitHubIssueCreator] ${msg}") }
    }

    /**
     * Execute an action with retry logic.
     *
     * @param isMutating Whether this is a POST/PATCH call (affects rate-limit gating)
     * @param action Closure that performs the API call and returns an ApiResponse
     * @return ExecutionResult (Success or SafeFail)
     */
    ExecutionResult execute(boolean isMutating, Closure<ApiResponse> action) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                // Check rate-limit gate
                if (!rateLimitManager.canProceed(isMutating)) {
                    long waitUntilReset = rateLimitManager.getWaitUntilReset()
                    if (waitUntilReset > maxWaitMs) {
                        return new ExecutionResult.SafeFail(
                            reason: "Rate limit reset too far in future (${waitUntilReset}ms > max ${maxWaitMs}ms)",
                            shouldAlert: true
                        )
                    }
                    if (waitUntilReset > 0) {
                        logger.call("Rate limit low, waiting ${waitUntilReset}ms for reset (attempt ${attempt + 1}/${maxRetries + 1})")
                        sleeper.call(waitUntilReset)
                        // Re-check after waiting
                        if (!rateLimitManager.canProceed(isMutating)) {
                            if (attempt == maxRetries) break
                            continue
                        }
                    } else {
                        if (attempt == maxRetries) break
                        continue
                    }
                }

                // Execute the action
                ApiResponse response = action.call()

                // Update rate-limit state from response
                rateLimitManager.updateFromHeaders(response.headers, isMutating)

                // Classify response
                switch (classifyResponse(response)) {
                    case ResponseClass.SUCCESS:
                        return new ExecutionResult.Success(response: response)

                    case ResponseClass.RATE_LIMITED:
                        if (attempt == maxRetries) {
                            return new ExecutionResult.SafeFail(
                                reason: "Rate limited after ${maxRetries + 1} attempts (HTTP ${response.statusCode})",
                                shouldAlert: true
                            )
                        }
                        long waitTime = rateLimitManager.getWaitTime(response.headers, attempt)
                        long jitter = (long) (Math.random() * 1000L)
                        long totalWait = waitTime + jitter
                        if (totalWait > maxWaitMs) {
                            return new ExecutionResult.SafeFail(
                                reason: "Required wait ${totalWait}ms exceeds max ${maxWaitMs}ms",
                                shouldAlert: true
                            )
                        }
                        logger.call("Rate limited (HTTP ${response.statusCode}), waiting ${totalWait}ms (attempt ${attempt + 1}/${maxRetries + 1})")
                        sleeper.call(totalWait)
                        break

                    case ResponseClass.AUTH_ERROR:
                        return new ExecutionResult.SafeFail(
                            reason: "Authentication/authorization error (HTTP ${response.statusCode}): ${sanitizeMessage(response.body)}",
                            shouldAlert: true
                        )

                    case ResponseClass.TRANSIENT:
                        if (attempt == maxRetries) {
                            return new ExecutionResult.SafeFail(
                                reason: "Transient error after ${maxRetries + 1} attempts (HTTP ${response.statusCode})",
                                shouldAlert: true
                            )
                        }
                        long backoff = (long) (Math.pow(2, attempt) * 2000L + Math.random() * 1000L)
                        logger.call("Transient error (HTTP ${response.statusCode}), backing off ${backoff}ms (attempt ${attempt + 1}/${maxRetries + 1})")
                        sleeper.call(Math.min(backoff, maxWaitMs))
                        break

                    case ResponseClass.CLIENT_ERROR:
                        return new ExecutionResult.SafeFail(
                            reason: "Client error (HTTP ${response.statusCode}): ${sanitizeMessage(response.body)}",
                            shouldAlert: false
                        )
                }

            } catch (Exception e) {
                // Connection errors, timeouts, etc.
                if (attempt == maxRetries) {
                    return new ExecutionResult.SafeFail(
                        reason: "Connection error after ${maxRetries + 1} attempts: ${sanitizeMessage(e.message)}",
                        shouldAlert: true
                    )
                }
                long backoff = (long) (Math.pow(2, attempt) * 2000L + Math.random() * 1000L)
                logger.call("Connection error: ${sanitizeMessage(e.message)}, backing off ${backoff}ms (attempt ${attempt + 1}/${maxRetries + 1})")
                sleeper.call(Math.min(backoff, maxWaitMs))
            }
        }

        return new ExecutionResult.SafeFail(
            reason: "Exhausted all ${maxRetries + 1} retry attempts",
            shouldAlert: true
        )
    }

    /**
     * Classify an HTTP response into an error category.
     */
    private ResponseClass classifyResponse(ApiResponse response) {
        int code = response.statusCode
        if (code >= 200 && code < 300) return ResponseClass.SUCCESS
        if (code == 401 || code == 404) return ResponseClass.AUTH_ERROR
        if (code == 403 || code == 429) return ResponseClass.RATE_LIMITED
        if (code >= 500) return ResponseClass.TRANSIENT
        return ResponseClass.CLIENT_ERROR
    }

    /**
     * Remove any potential token leaks from error messages.
     */
    private static String sanitizeMessage(String message) {
        if (!message) return '<no message>'
        // Strip anything that looks like a token (40+ hex chars or base64)
        return message
            .replaceAll(/(?i)(bearer\s+)\S+/, '$1***REDACTED***')
            .replaceAll(/ghp_[A-Za-z0-9_]+/, '***REDACTED***')
            .replaceAll(/ghs_[A-Za-z0-9_]+/, '***REDACTED***')
            .replaceAll(/github_pat_[A-Za-z0-9_]+/, '***REDACTED***')
    }
}

/**
 * Classification of HTTP responses for retry decisions.
 */
enum ResponseClass {
    SUCCESS,        // 2xx — done
    RATE_LIMITED,   // 403 (rate limit) or 429 — retry with backoff
    AUTH_ERROR,     // 401, 404 — no retry (credential issue)
    TRANSIENT,      // 5xx — retry with backoff
    CLIENT_ERROR    // Other 4xx — no retry
}

/**
 * Result of an execution attempt.
 */
abstract class ExecutionResult implements Serializable {
    private static final long serialVersionUID = 1L

    static class Success extends ExecutionResult {
        ApiResponse response
    }

    static class SafeFail extends ExecutionResult {
        String reason
        boolean shouldAlert
    }

    boolean isSuccess() { return this instanceof Success }
    boolean isSafeFail() { return this instanceof SafeFail }
}

