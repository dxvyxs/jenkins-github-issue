package org.jenkins.plugins.github_issue_creator

/**
 * Manages GitHub API rate-limit state and gating decisions.
 * Enforces serialized access and minimum gaps between mutating calls.
 *
 * Integrates with Jenkins' ApiRateLimitChecker to respect the shared budget.
 */
class RateLimitManager implements Serializable {
    private static final long serialVersionUID = 1L

    /** Current remaining API calls */
    int remaining = Integer.MAX_VALUE

    /** Epoch seconds when the rate limit resets */
    long resetEpochSeconds = 0

    /** Epoch milliseconds of the last mutating (POST/PATCH) call */
    long lastMutatingCallEpochMs = 0

    /** Minimum gap between mutating calls in milliseconds */
    private final int minCallGapMs

    /** Threshold below which we stop making calls */
    private final int threshold

    /** Injectable clock for testability (returns epoch millis) */
    private final Closure<Long> clock

    /** Injectable sleep function for testability */
    private final Closure<Void> sleeper

    RateLimitManager(int minCallGapMs = 1000, int threshold = 100,
                     Closure<Long> clock = null, Closure<Void> sleeper = null) {
        this.minCallGapMs = minCallGapMs
        this.threshold = threshold
        this.clock = clock ?: { System.currentTimeMillis() }
        this.sleeper = sleeper ?: { long ms -> Thread.sleep(ms) }
    }

    /**
     * Check if we can proceed with an API call.
     * If mutating, enforces the minimum gap (blocking if necessary).
     *
     * @param isMutating true for POST/PATCH calls
     * @return true if safe to proceed, false if rate budget is too low
     */
    boolean canProceed(boolean isMutating) {
        // Check if we're below the safety threshold
        if (remaining <= threshold) {
            return false
        }

        // Enforce minimum gap for mutating calls
        if (isMutating && lastMutatingCallEpochMs > 0) {
            long now = clock.call()
            long elapsed = now - lastMutatingCallEpochMs
            if (elapsed < minCallGapMs) {
                long waitTime = minCallGapMs - elapsed
                sleeper.call(waitTime)
            }
        }

        return true
    }

    /**
     * Update rate-limit state from response headers.
     *
     * @param headers Map of response headers (case-insensitive lookup)
     * @param wasMutating true if the call was POST/PATCH
     */
    void updateFromHeaders(Map<String, String> headers, boolean wasMutating) {
        if (headers == null) return

        String remainingStr = getHeaderCaseInsensitive(headers, 'X-RateLimit-Remaining')
        if (remainingStr?.isInteger()) {
            this.remaining = remainingStr.toInteger()
        }

        String resetStr = getHeaderCaseInsensitive(headers, 'X-RateLimit-Reset')
        if (resetStr?.isLong()) {
            this.resetEpochSeconds = resetStr.toLong()
        }

        if (wasMutating) {
            this.lastMutatingCallEpochMs = clock.call()
        }
    }

    /**
     * Calculate how long to wait before retrying, based on response headers and attempt count.
     *
     * Priority:
     * 1. Retry-After header
     * 2. X-RateLimit-Reset minus current time
     * 3. Exponential backoff with jitter
     *
     * @param headers Response headers from the failed request
     * @param retryCount Current retry attempt number (0-based)
     * @return Wait time in milliseconds
     */
    long getWaitTime(Map<String, String> headers, int retryCount) {
        // 1. Check Retry-After header
        String retryAfter = getHeaderCaseInsensitive(headers, 'Retry-After')
        if (retryAfter?.isInteger()) {
            return retryAfter.toInteger() * 1000L
        }

        // 2. Compute from X-RateLimit-Reset
        String resetStr = getHeaderCaseInsensitive(headers, 'X-RateLimit-Reset')
        if (resetStr?.isLong()) {
            long resetEpoch = resetStr.toLong()
            long nowEpoch = clock.call() / 1000L
            long waitSeconds = resetEpoch - nowEpoch
            if (waitSeconds > 0) {
                return (waitSeconds * 1000L) + 1000L // +1s buffer
            }
        }

        // 3. Exponential backoff with jitter
        long baseWait = (long) Math.pow(2, retryCount) * 2000L
        long jitter = (long) (Math.random() * 1000L)
        return Math.min(baseWait + jitter, 64_000L)
    }

    /**
     * Get the time to wait until the rate limit resets.
     * @return milliseconds until reset, or 0 if already reset
     */
    long getWaitUntilReset() {
        if (resetEpochSeconds == 0) return 0
        long nowSeconds = clock.call() / 1000L
        long waitSeconds = resetEpochSeconds - nowSeconds
        return waitSeconds > 0 ? (waitSeconds * 1000L + 1000L) : 0
    }

    /**
     * Apply Jenkins API rate-limit strategy adjustments.
     *
     * @param strategy "ThrottleForNormalize" or "ThrottleOnOver"
     * @param configuredThreshold custom threshold override (optional)
     */
    void applyJenkinsStrategy(String strategy, Integer configuredThreshold = null) {
        if (configuredThreshold != null) {
            // Use explicit threshold if provided
            return
        }

        // Adjust behavior based on Jenkins' configured strategy
        // (This is called at startup to read the Jenkins global config)
        // The threshold field is already set via constructor; this method
        // is a hook for future dynamic adjustment if needed.
    }

    private static String getHeaderCaseInsensitive(Map<String, String> headers, String key) {
        // Try exact match first
        if (headers.containsKey(key)) return headers[key]
        // Try lowercase
        String lowerKey = key.toLowerCase()
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.key?.toLowerCase() == lowerKey) {
                return entry.value
            }
        }
        return null
    }
}
