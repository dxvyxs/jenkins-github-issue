package org.jenkins.plugins.github_issue_creator

import java.security.MessageDigest

/**
 * Computes a deterministic identity for a SARIF security finding.
 *
 * Identity:
 *   jobName + CVE
 *
 * The same CVE reported by different scanners or at different
 * file locations will map to the same GitHub issue.
 */
class FailureIdentityEngine implements Serializable {

    private static final long serialVersionUID = 1L

    private static final String MARKER_PREFIX = '<!-- jenkins-issue-id:'
    private static final String MARKER_SUFFIX = ' -->'

    /**
     * Compute the identity for a SARIF security finding.
     *
     * @param jobName Jenkins job name
     * @param result SARIF result parsed as a Map
     */
    static FailureIdentity computeSarifFinding(
        String jobName,
        Map result
    ) {
        String cve = extractCve(result)

        if (!cve) {
            throw new IllegalArgumentException(
                'SARIF finding does not contain a CVE'
            )
        }

        String identityInput = "${jobName}|CVE|${cve}"
        String hash = computeHash(identityInput)
        String marker = "${MARKER_PREFIX}${hash}${MARKER_SUFFIX}"

        return new FailureIdentity(
            hash: hash,
            marker: marker,
            identityInput: identityInput
        )
    }

    /**
     * Extract the CVE from the SARIF result.
     */
    static String extractCve(Map result) {
        String cve

        cve = findCve(result?.ruleId)
        if (cve) {
            return cve
        }

        cve = findCve(result?.rule?.id)
        if (cve) {
            return cve
        }

        cve = findCve(result?.message?.text)
        if (cve) {
            return cve
        }

        cve = findCve(result?.rule?.help?.text)
        if (cve) {
            return cve
        }

        return null
    }

    /**
     * Find a CVE identifier in text.
     */
    private static String findCve(String text) {
        if (!text) {
            return null
        }

        def matcher = text =~ /(?i)\bCVE-\d{4}-\d{4,}\b/

        if (matcher.find()) {
            return matcher.group().toUpperCase()
        }

        return null
    }
    static String extractRuleId(Map result) {
        return result?.ruleId ?: result?.rule?.id ?: 'unknown'
    }

    /**
     * Extract the first SARIF artifact location.
     */
    static String extractLocation(Map result) {
        return result?.locations?.getAt(0)
            ?.physicalLocation
            ?.artifactLocation
            ?.uri ?: 'unknown'
    }


    /**
     * Extract the identity marker from an existing GitHub issue body.
     */
    static String extractMarkerHash(String issueBody) {
        if (!issueBody) {
            return null
        }

        int startIdx = issueBody.indexOf(MARKER_PREFIX)

        if (startIdx < 0) {
            return null
        }

        int hashStart = startIdx + MARKER_PREFIX.length()
        int endIdx = issueBody.indexOf(MARKER_SUFFIX, hashStart)

        if (endIdx < 0) {
            return null
        }

        return issueBody.substring(hashStart, endIdx).trim()
    }

    /**
     * Compute SHA-256 and truncate to 16 hexadecimal characters.
     */
    private static String computeHash(String input) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        byte[] hashBytes = digest.digest(input.getBytes('UTF-8'))

        String fullHex = hashBytes.collect {
            String.format('%02x', it)
        }.join('')

        return fullHex.substring(0, 16)
    }
}