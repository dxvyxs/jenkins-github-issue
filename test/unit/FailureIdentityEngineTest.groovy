package org.jenkins.plugins.github_issue_creator

import groovy.test.GroovyTestCase

/**
 * Unit tests for FailureIdentityEngine.
 * Verifies:
 * - Deterministic identity computation (same inputs → same hash)
 * - Stability across volatile tokens (timestamps, UUIDs, build numbers)
 * - Correct normalization of log lines
 * - Marker embedding and extraction
 * - Custom failure key override
 * - Edge cases (empty logs, no meaningful lines)
 */
class FailureIdentityEngineTest extends GroovyTestCase {

    // ========== Determinism Tests ==========

    void testSameInputsProduceSameHash() {
        def id1 = FailureIdentityEngine.compute('my-job', 'Build', 'Error: compilation failed')
        def id2 = FailureIdentityEngine.compute('my-job', 'Build', 'Error: compilation failed')

        assertEquals(id1.hash, id2.hash)
        assertEquals(id1.marker, id2.marker)
    }

    void testDifferentJobNameProducesDifferentHash() {
        def id1 = FailureIdentityEngine.compute('job-a', 'Build', 'Error: compilation failed')
        def id2 = FailureIdentityEngine.compute('job-b', 'Build', 'Error: compilation failed')

        assertNotEquals(id1.hash, id2.hash)
    }

    void testDifferentStageProducesDifferentHash() {
        def id1 = FailureIdentityEngine.compute('my-job', 'Build', 'Error: compilation failed')
        def id2 = FailureIdentityEngine.compute('my-job', 'Test', 'Error: compilation failed')

        assertNotEquals(id1.hash, id2.hash)
    }

    void testDifferentErrorProducesDifferentHash() {
        def id1 = FailureIdentityEngine.compute('my-job', 'Build', 'Error: compilation failed')
        def id2 = FailureIdentityEngine.compute('my-job', 'Build', 'Error: out of memory')

        assertNotEquals(id1.hash, id2.hash)
    }

    void testHashIs16HexChars() {
        def id = FailureIdentityEngine.compute('job', 'stage', 'error message')

        assertEquals(16, id.hash.length())
        assertTrue(id.hash ==~ /^[0-9a-f]{16}$/)
    }

    // ========== Normalization / Stability Tests ==========

    void testTimestampsAreStripped() {
        def log1 = '2024-01-15T14:30:22.123Z Error: connection refused'
        def log2 = '2025-08-18T09:00:00.000Z Error: connection refused'

        def id1 = FailureIdentityEngine.compute('job', 'stage', log1)
        def id2 = FailureIdentityEngine.compute('job', 'stage', log2)

        assertEquals(id1.hash, id2.hash)
    }

    void testLogTimestampsAreStripped() {
        def log1 = '2024-01-15 14:30:22,123 ERROR - NullPointerException at line 42'
        def log2 = '2025-12-31 23:59:59,999 ERROR - NullPointerException at line 42'

        def id1 = FailureIdentityEngine.compute('job', 'stage', log1)
        def id2 = FailureIdentityEngine.compute('job', 'stage', log2)

        assertEquals(id1.hash, id2.hash)
    }

    void testUnixTimestampsAreStripped() {
        def log1 = 'Failed at 1705312222123 with error code 1'
        def log2 = 'Failed at 1724000000000 with error code 1'

        def id1 = FailureIdentityEngine.compute('job', 'stage', log1)
        def id2 = FailureIdentityEngine.compute('job', 'stage', log2)

        assertEquals(id1.hash, id2.hash)
    }

    void testUUIDsAreStripped() {
        def log1 = 'Request a1b2c3d4-e5f6-7890-abcd-ef1234567890 failed'
        def log2 = 'Request ffffffff-aaaa-bbbb-cccc-dddddddddddd failed'

        def id1 = FailureIdentityEngine.compute('job', 'stage', log1)
        def id2 = FailureIdentityEngine.compute('job', 'stage', log2)

        assertEquals(id1.hash, id2.hash)
    }

    void testBuildNumbersAreStripped() {
        def log1 = 'Build #142 failed in stage Deploy'
        def log2 = 'Build #999 failed in stage Deploy'

        def id1 = FailureIdentityEngine.compute('job', 'stage', log1)
        def id2 = FailureIdentityEngine.compute('job', 'stage', log2)

        assertEquals(id1.hash, id2.hash)
    }

    void testMemoryAddressesAreStripped() {
        def log1 = 'Segfault at 0x7fff5fbff8c8 in module libfoo.so'
        def log2 = 'Segfault at 0x00000000dead in module libfoo.so'

        def id1 = FailureIdentityEngine.compute('job', 'stage', log1)
        def id2 = FailureIdentityEngine.compute('job', 'stage', log2)

        assertEquals(id1.hash, id2.hash)
    }

    void testPortNumbersAreStripped() {
        def log1 = 'Connection refused to localhost:8080'
        def log2 = 'Connection refused to localhost:9443'

        def id1 = FailureIdentityEngine.compute('job', 'stage', log1)
        def id2 = FailureIdentityEngine.compute('job', 'stage', log2)

        assertEquals(id1.hash, id2.hash)
    }

    void testMultipleVolatileTokensInSameLine() {
        def log1 = '2024-01-15T10:00:00Z Build #50 failed at 0x1234abcd on port :5432 request a1b2c3d4-e5f6-7890-abcd-ef1234567890'
        def log2 = '2025-06-20T22:15:30Z Build #200 failed at 0xdeadbeef on port :3306 request ffffffff-aaaa-bbbb-cccc-dddddddddddd'

        def id1 = FailureIdentityEngine.compute('job', 'stage', log1)
        def id2 = FailureIdentityEngine.compute('job', 'stage', log2)

        assertEquals(id1.hash, id2.hash)
    }

    void testWhitespaceIsCollapsed() {
        def log1 = 'Error:    too   many    spaces'
        def log2 = 'Error: too many spaces'

        def id1 = FailureIdentityEngine.compute('job', 'stage', log1)
        def id2 = FailureIdentityEngine.compute('job', 'stage', log2)

        assertEquals(id1.hash, id2.hash)
    }

    // ========== Line Extraction Tests ==========

    void testSkipsBlankLines() {
        def log = """

Error: something broke

Another error
"""
        def lines = FailureIdentityEngine.extractMeaningfulLines(log, 5)

        assertFalse(lines.any { it.trim().isEmpty() })
        assertTrue(lines.contains('Error: something broke'))
        assertTrue(lines.contains('Another error'))
    }

    void testSkipsPipelineProgressMarkers() {
        def log = """[Pipeline] stage
[Pipeline] { (Build)
Error: compilation failed at Main.java:10
[Pipeline] }
NullPointerException in module X"""

        def lines = FailureIdentityEngine.extractMeaningfulLines(log, 5)

        assertFalse(lines.any { it.startsWith('[Pipeline]') })
        assertTrue(lines.contains('Error: compilation failed at Main.java:10'))
        assertTrue(lines.contains('NullPointerException in module X'))
    }

    void testSkipsSeparatorLines() {
        def log = """Error: build failed
---
===
Another error"""

        def lines = FailureIdentityEngine.extractMeaningfulLines(log, 5)

        assertEquals(2, lines.size())
        assertTrue(lines.contains('Error: build failed'))
        assertTrue(lines.contains('Another error'))
    }

    void testLimitsToMaxLines() {
        def log = (1..20).collect { "Error line ${it}" }.join('\n')

        def lines = FailureIdentityEngine.extractMeaningfulLines(log, 5)

        assertEquals(5, lines.size())
        assertEquals('Error line 1', lines[0])
        assertEquals('Error line 5', lines[4])
    }

    void testEmptyLogReturnsPlaceholder() {
        def lines = FailureIdentityEngine.extractMeaningfulLines('', 5)
        assertEquals(['<empty-log>'], lines)

        def linesNull = FailureIdentityEngine.extractMeaningfulLines(null, 5)
        assertEquals(['<empty-log>'], linesNull)
    }

    void testOnlySkippableLinesReturnsPlaceholder() {
        def log = """[Pipeline] stage
[Pipeline] { (Build)
[Pipeline] }
---
"""
        def lines = FailureIdentityEngine.extractMeaningfulLines(log, 5)
        assertEquals(['<no-meaningful-lines>'], lines)
    }

    // ========== Marker Tests ==========

    void testMarkerFormat() {
        def id = FailureIdentityEngine.compute('job', 'stage', 'error')

        assertTrue(id.marker.startsWith('<!-- jenkins-issue-id:'))
        assertTrue(id.marker.endsWith(' -->'))
        assertTrue(id.marker.contains(id.hash))
    }

    void testExtractMarkerHashFromBody() {
        def id = FailureIdentityEngine.compute('job', 'stage', 'error')

        String issueBody = """
## Build Failure Report
Some content here...

${id.marker}"""

        String extracted = FailureIdentityEngine.extractMarkerHash(issueBody)
        assertEquals(id.hash, extracted)
    }

    void testExtractMarkerHashFromBodyWithSurroundingContent() {
        String hash = 'abcdef0123456789'
        String body = "lots of text\n\n<!-- jenkins-issue-id:${hash} -->\n\nmore text"

        String extracted = FailureIdentityEngine.extractMarkerHash(body)
        assertEquals(hash, extracted)
    }

    void testExtractMarkerHashReturnsNullWhenNoMarker() {
        String body = "Just a regular issue body with no marker"
        assertNull(FailureIdentityEngine.extractMarkerHash(body))
    }

    void testExtractMarkerHashReturnsNullForNullBody() {
        assertNull(FailureIdentityEngine.extractMarkerHash(null))
    }

    void testExtractMarkerHashReturnsNullForEmptyBody() {
        assertNull(FailureIdentityEngine.extractMarkerHash(''))
    }

    void testExtractMarkerHashHandlesMalformedMarker() {
        // Missing closing comment
        String body = "<!-- jenkins-issue-id:abc123"
        assertNull(FailureIdentityEngine.extractMarkerHash(body))
    }

    // ========== Custom Failure Key Tests ==========

    void testCustomFailureKeyOverridesLogAnalysis() {
        def id1 = FailureIdentityEngine.compute('job', 'stage', 'completely different log 1', 'my-custom-key')
        def id2 = FailureIdentityEngine.compute('job', 'stage', 'completely different log 2', 'my-custom-key')

        assertEquals(id1.hash, id2.hash)
    }

    void testCustomFailureKeyStillUsesJobAndStage() {
        def id1 = FailureIdentityEngine.compute('job-a', 'stage', 'x', 'same-key')
        def id2 = FailureIdentityEngine.compute('job-b', 'stage', 'x', 'same-key')

        // Different jobs with same custom key should still differ
        assertNotEquals(id1.hash, id2.hash)
    }

    void testNullCustomKeyUsesLogAnalysis() {
        def idNull = FailureIdentityEngine.compute('job', 'stage', 'Error: specific', null)
        def idEmpty = FailureIdentityEngine.compute('job', 'stage', 'Error: specific', '')
        def idBlank = FailureIdentityEngine.compute('job', 'stage', 'Error: specific', '   ')

        // All should use log analysis and produce the same hash
        assertEquals(idNull.hash, idEmpty.hash)
        assertEquals(idNull.hash, idBlank.hash)
    }

    // ========== Idempotency Tests ==========

    void testIdempotencyAcrossMultipleCalls() {
        // Simulate running the same stage twice
        def results = (1..10).collect {
            FailureIdentityEngine.compute(
                'my-pipeline/main',
                'Build',
                '2024-01-15T10:00:00Z FATAL: npm ERR! Build failed\nnpm ERR! code ELIFECYCLE'
            )
        }

        def firstHash = results[0].hash
        assertTrue(results.every { it.hash == firstHash })
    }

    void testIdentityStableAcrossRetriesWithDifferentTimestamps() {
        // Same error, different timestamps (simulating retries of the same build)
        def log1 = """2024-01-15T10:00:00Z [ERROR] Failed to execute goal
2024-01-15T10:00:01Z at org.apache.maven.lifecycle.internal.MojoExecutor.execute(MojoExecutor.java:213)
2024-01-15T10:00:01Z Caused by: java.lang.OutOfMemoryError: Java heap space"""

        def log2 = """2024-01-15T10:05:30Z [ERROR] Failed to execute goal
2024-01-15T10:05:31Z at org.apache.maven.lifecycle.internal.MojoExecutor.execute(MojoExecutor.java:213)
2024-01-15T10:05:31Z Caused by: java.lang.OutOfMemoryError: Java heap space"""

        def id1 = FailureIdentityEngine.compute('maven-build', 'Compile', log1)
        def id2 = FailureIdentityEngine.compute('maven-build', 'Compile', log2)

        assertEquals(id1.hash, id2.hash)
    }

    // ========== Normalization Specific Tests ==========

    void testNormalizeLineStripsISO8601() {
        String normalized = FailureIdentityEngine.normalizeLine(
            '2024-01-15T14:30:22.123Z ERROR Something failed'
        )
        assertFalse(normalized.contains('2024'))
        assertTrue(normalized.contains('ERROR Something failed'))
    }

    void testNormalizeLineStripsUUID() {
        String normalized = FailureIdentityEngine.normalizeLine(
            'Request a1b2c3d4-e5f6-7890-abcd-ef1234567890 failed with timeout'
        )
        assertFalse(normalized.contains('a1b2c3d4'))
        assertTrue(normalized.contains('Request'))
        assertTrue(normalized.contains('failed with timeout'))
    }

    void testNormalizeLinePreservesMeaningfulContent() {
        String input = 'java.lang.NullPointerException: Cannot invoke method getValue() on null object'
        String normalized = FailureIdentityEngine.normalizeLine(input)

        assertEquals(input, normalized)
    }

    void testNormalizeLineHandlesEmptyInput() {
        assertEquals('', FailureIdentityEngine.normalizeLine(''))
        assertEquals('', FailureIdentityEngine.normalizeLine(null))
    }

    // ========== Collision Resistance Tests ==========

    void testDifferentErrorsProduceDifferentHashes() {
        def errors = [
            'NullPointerException at line 42',
            'ArrayIndexOutOfBoundsException: 5',
            'Connection refused to database',
            'Timeout waiting for response',
            'Permission denied: /etc/secret'
        ]

        def hashes = errors.collect {
            FailureIdentityEngine.compute('job', 'stage', it).hash
        }

        // All hashes should be unique
        assertEquals(hashes.size(), hashes.toSet().size())
    }
}
