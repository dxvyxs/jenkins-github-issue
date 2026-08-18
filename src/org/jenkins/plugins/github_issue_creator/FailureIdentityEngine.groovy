package org.jenkins.plugins.github_issue_creator

import java.security.MessageDigest

/**
 * Computes a deterministic, stable identity for a build failure.
 * Used for deduplication: same failure produces same identity hash.
 *
 * Algorithm:
 * 1. Extract first N meaningful error lines from failure log
 * 2. Normalize each line (strip volatile tokens)
 * 3. Concatenate with job/stage context
 * 4. SHA-256 hash, truncated to 16 hex chars
 */
class FailureIdentityEngine implements Serializable {
    private static final long serialVersionUID = 1L

    /** Number of meaningful error lines to consider */
    private static final int DEFAULT_ERROR_LINES = 5

    /** HTML comment marker prefix */
    private static final String MARKER_PREFIX = '<!-- jenkins-issue-id:'
    private static final String MARKER_SUFFIX = ' -->'

    // Patterns for volatile tokens that should be stripped during normalization
    private static final List<Map<String, String>> NORMALIZATION_PATTERNS = [
        // ISO-8601 timestamps
        [pattern: /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[^\s]*/, replacement: '<TIMESTAMP>'],
        // Common log timestamps: 2024-01-15 14:30:22,123
        [pattern: /\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}[,\.]\d{3}/, replacement: '<TIMESTAMP>'],
        // Unix timestamps (10 or 13 digits)
        [pattern: /\b\d{10,13}\b/, replacement: '<EPOCH>'],
        // UUIDs
        [pattern: /[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/, replacement: '<UUID>'],
        // Build numbers: #123
        [pattern: /#\d+/, replacement: '#<BUILD>'],
        // Memory addresses: 0x7fff5fbff8c8
        [pattern: /0x[0-9a-fA-F]+/, replacement: '<ADDR>'],
        // Port numbers in host:port context
        [pattern: /:\d{4,5}\b/, replacement: ':<PORT>'],
        // Temporary file paths with random components
        [pattern: /\/tmp\/[^\s]+/, replacement: '<TMPPATH>'],
        // Process IDs
        [pattern: /\bpid[=:\s]+\d+/i, replacement: 'pid=<PID>'],
    ]

    // Lines that are not meaningful for identity computation
    private static final List<String> SKIP_PATTERNS = [
        /^\s*$/, // blank lines
        /^\s*[\-=]{3,}\s*$/, // separator lines
        /^\s*\.\.\.\s*$/, // ellipsis
        /^\[Pipeline\]\s/, // Jenkins pipeline progress markers
        /^\+\s/, // shell trace lines (set -x)
        /^>\s/, // command echo
    ]

    /**
     * Compute the failure identity hash.
     *
     * @param jobName Jenkins job name
     * @param stageName Pipeline stage name
     * @param failureLog Raw failure log output
     * @param customFailureKey Optional override key (bypasses log analysis)
     * @return FailureIdentity containing the hash and marker
     */
    static FailureIdentity compute(String jobName, String stageName, String failureLog, String customFailureKey = null) {
        String identityInput

        if (customFailureKey?.trim()) {
            identityInput = "${jobName}|${stageName}|${customFailureKey.trim()}"
        } else {
            List<String> meaningfulLines = extractMeaningfulLines(failureLog, DEFAULT_ERROR_LINES)
            List<String> normalizedLines = meaningfulLines.collect { normalizeLine(it) }
            identityInput = "${jobName}|${stageName}|${normalizedLines.join('\n')}"
        }

        String hash = computeHash(identityInput)
        String marker = "${MARKER_PREFIX}${hash}${MARKER_SUFFIX}"

        return new FailureIdentity(
            hash: hash,
            marker: marker,
            identityInput: identityInput
        )
    }

    /**
     * Extract the marker from an issue body, if present.
     *
     * @param issueBody The full issue body text
     * @return The identity hash if found, null otherwise
     */
    static String extractMarkerHash(String issueBody) {
        if (!issueBody) return null

        int startIdx = issueBody.indexOf(MARKER_PREFIX)
        if (startIdx < 0) return null

        int hashStart = startIdx + MARKER_PREFIX.length()
        int endIdx = issueBody.indexOf(MARKER_SUFFIX, hashStart)
        if (endIdx < 0) return null

        return issueBody.substring(hashStart, endIdx).trim()
    }

    /**
     * Extract first N meaningful lines from a failure log.
     * Skips blank lines, separators, and Jenkins pipeline markers.
     */
    static List<String> extractMeaningfulLines(String log, int maxLines) {
        if (!log?.trim()) return ['<empty-log>']

        List<String> lines = log.split('\n') as List<String>
        List<String> meaningful = []

        for (String line : lines) {
            if (meaningful.size() >= maxLines) break
            if (isSkippableLine(line)) continue
            meaningful.add(line.trim())
        }

        return meaningful.isEmpty() ? ['<no-meaningful-lines>'] : meaningful
    }

    /**
     * Normalize a single log line by removing volatile tokens.
     */
    static String normalizeLine(String line) {
        if (!line) return ''

        String result = line
        for (Map<String, String> pattern : NORMALIZATION_PATTERNS) {
            result = result.replaceAll(pattern.pattern, pattern.replacement)
        }

        // Collapse whitespace
        result = result.replaceAll(/\s+/, ' ').trim()

        return result
    }

    /**
     * Determine if a line should be skipped (not meaningful for identity).
     */
    private static boolean isSkippableLine(String line) {
        for (String pattern : SKIP_PATTERNS) {
            if (line ==~ pattern) return true
        }
        return false
    }

    /**
     * Compute SHA-256 hash, truncated to 16 hex characters (64 bits).
     */
    private static String computeHash(String input) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        byte[] hashBytes = digest.digest(input.getBytes('UTF-8'))
        String fullHex = hashBytes.collect { String.format('%02x', it) }.join('')
        return fullHex.substring(0, 16)
    }
}

/**
 * Value object representing a computed failure identity.
 */
class FailureIdentity implements Serializable {
    private static final long serialVersionUID = 1L

    /** 16-character hex hash */
    String hash

    /** Full HTML comment marker for embedding in issue body */
    String marker

    /** The raw input string that was hashed (useful for debugging) */
    String identityInput
}
