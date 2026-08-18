package org.jenkins.plugins.github_issue_creator

import groovy.test.GroovyTestCase

/**
 * Unit tests for RateLimitManager.
 * Tests the rate-limit state machine, gating logic, and wait-time computation.
 * Uses injectable clock and sleeper to avoid real delays.
 */
class RateLimitManagerTest extends GroovyTestCase {

    long currentTimeMs
    List<Long> sleepCalls

    void setUp() {
        currentTimeMs = 1000000L
        sleepCalls = []
    }

    private RateLimitManager buildManager(int minGapMs = 1000, int threshold = 100) {
        return new RateLimitManager(
            minGapMs,
            threshold,
            { currentTimeMs },           // clock
            { long ms -> sleepCalls.add(ms); currentTimeMs += ms }  // sleeper
        )
    }

    // ========== canProceed Tests ==========

    void testCanProceedWhenRemainingAboveThreshold() {
        def mgr = buildManager()
        mgr.remaining = 500

        assertTrue(mgr.canProceed(false))
        assertTrue(mgr.canProceed(true))
    }

    void testCannotProceedWhenRemainingAtThreshold() {
        def mgr = buildManager(1000, 100)
        mgr.remaining = 100

        assertFalse(mgr.canProceed(false))
        assertFalse(mgr.canProceed(true))
    }

    void testCannotProceedWhenRemainingBelowThreshold() {
        def mgr = buildManager(1000, 100)
        mgr.remaining = 50

        assertFalse(mgr.canProceed(false))
    }

    void testNonMutatingCallsDoNotEnforceGap() {
        def mgr = buildManager(1000, 10)
        mgr.remaining = 500
        mgr.lastMutatingCallEpochMs = currentTimeMs // just made a call

        assertTrue(mgr.canProceed(false))
        assertTrue(sleepCalls.isEmpty()) // no sleep for GET
    }

    void testMutatingCallEnforcesGap() {
        def mgr = buildManager(1000, 10)
        mgr.remaining = 500
        mgr.lastMutatingCallEpochMs = currentTimeMs - 200 // 200ms ago (need 1000ms gap)

        assertTrue(mgr.canProceed(true))
        // Should have slept for 800ms (1000 - 200)
        assertEquals(1, sleepCalls.size())
        assertEquals(800L, sleepCalls[0])
    }

    void testMutatingCallNoSleepWhenGapSufficient() {
        def mgr = buildManager(1000, 10)
        mgr.remaining = 500
        mgr.lastMutatingCallEpochMs = currentTimeMs - 1500 // 1500ms ago (> 1000ms gap)

        assertTrue(mgr.canProceed(true))
        assertTrue(sleepCalls.isEmpty())
    }

    void testFirstMutatingCallNoSleep() {
        def mgr = buildManager(1000, 10)
        mgr.remaining = 500
        // lastMutatingCallEpochMs is 0 (no prior call)

        assertTrue(mgr.canProceed(true))
        assertTrue(sleepCalls.isEmpty())
    }

    // ========== updateFromHeaders Tests ==========

    void testUpdateFromHeadersParsesRemainingAndReset() {
        def mgr = buildManager()
        mgr.updateFromHeaders([
            'X-RateLimit-Remaining': '4500',
            'X-RateLimit-Reset': '1700000000'
        ], false)

        assertEquals(4500, mgr.remaining)
        assertEquals(1700000000L, mgr.resetEpochSeconds)
    }

    void testUpdateFromHeadersUpdatesLastMutatingTime() {
        def mgr = buildManager()
        currentTimeMs = 5000000L

        mgr.updateFromHeaders([
            'X-RateLimit-Remaining': '100'
        ], true)

        assertEquals(5000000L, mgr.lastMutatingCallEpochMs)
    }

    void testUpdateFromHeadersDoesNotUpdateTimeForNonMutating() {
        def mgr = buildManager()
        mgr.lastMutatingCallEpochMs = 0

        mgr.updateFromHeaders(['X-RateLimit-Remaining': '100'], false)

        assertEquals(0L, mgr.lastMutatingCallEpochMs)
    }

    void testUpdateFromHeadersHandlesNullHeaders() {
        def mgr = buildManager()
        mgr.remaining = 999
        mgr.updateFromHeaders(null, false)
        assertEquals(999, mgr.remaining) // unchanged
    }

    void testUpdateFromHeadersCaseInsensitive() {
        def mgr = buildManager()
        mgr.updateFromHeaders([
            'x-ratelimit-remaining': '1234',
            'x-ratelimit-reset': '9876543210'
        ], false)

        assertEquals(1234, mgr.remaining)
        assertEquals(9876543210L, mgr.resetEpochSeconds)
    }

    // ========== getWaitTime Tests ==========

    void testGetWaitTimeUsesRetryAfterFirst() {
        def mgr = buildManager()
        long wait = mgr.getWaitTime(['Retry-After': '30'], 0)

        assertEquals(30_000L, wait)
    }

    void testGetWaitTimeUsesResetIfNoRetryAfter() {
        def mgr = buildManager()
        // Current time: 1000s (1000000ms), reset at 1060s → 60s wait + 1s buffer
        currentTimeMs = 1_000_000L
        long wait = mgr.getWaitTime(['X-RateLimit-Reset': '1060'], 0)

        assertEquals(61_000L, wait) // 60s + 1s buffer
    }

    void testGetWaitTimeFallsBackToExponentialBackoff() {
        def mgr = buildManager()

        long wait0 = mgr.getWaitTime([:], 0) // 2^0 * 2000 + jitter
        long wait1 = mgr.getWaitTime([:], 1) // 2^1 * 2000 + jitter
        long wait2 = mgr.getWaitTime([:], 2) // 2^2 * 2000 + jitter

        // Check ranges (jitter adds 0-1000ms)
        assertTrue("wait0=${wait0}", wait0 >= 2000 && wait0 <= 3000)
        assertTrue("wait1=${wait1}", wait1 >= 4000 && wait1 <= 5000)
        assertTrue("wait2=${wait2}", wait2 >= 8000 && wait2 <= 9000)
    }

    void testGetWaitTimeCapsAt64Seconds() {
        def mgr = buildManager()
        long wait = mgr.getWaitTime([:], 10) // 2^10 * 2000 = 2048000 → capped at 64000

        assertTrue("wait=${wait}", wait <= 64_000L)
    }

    void testGetWaitTimeIgnoresExpiredReset() {
        def mgr = buildManager()
        // Reset time is in the past
        currentTimeMs = 2_000_000L
        long wait = mgr.getWaitTime(['X-RateLimit-Reset': '1000'], 0)

        // Should fall through to exponential backoff since reset is past
        assertTrue("wait=${wait}", wait >= 2000) // backoff, not reset-based
    }

    // ========== getWaitUntilReset Tests ==========

    void testGetWaitUntilResetComputesCorrectly() {
        def mgr = buildManager()
        currentTimeMs = 1_000_000L // 1000 seconds
        mgr.resetEpochSeconds = 1060 // resets in 60 seconds

        long wait = mgr.getWaitUntilReset()
        assertEquals(61_000L, wait) // 60s + 1s buffer
    }

    void testGetWaitUntilResetReturnsZeroIfAlreadyReset() {
        def mgr = buildManager()
        currentTimeMs = 2_000_000L // 2000 seconds
        mgr.resetEpochSeconds = 1000 // already past

        assertEquals(0L, mgr.getWaitUntilReset())
    }

    void testGetWaitUntilResetReturnsZeroIfNotSet() {
        def mgr = buildManager()
        mgr.resetEpochSeconds = 0

        assertEquals(0L, mgr.getWaitUntilReset())
    }
}
