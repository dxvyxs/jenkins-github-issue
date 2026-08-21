package org.jenkins.plugins.github_issue_creator

/**
 * Configuration POJO for the GitHub Issue Creator component.
 * All settings are configurable via pipeline parameters or shared library config.
 */
class Config implements Serializable {
    private static final long serialVersionUID = 1L

    /** GitHub repository in "owner/repo" format */
    String githubRepo = 'dxvyxs/slsa-demo'

    /** Jenkins credential ID for GitHub authentication */
    String credentialId = 'github-issue-token'

    /** GitHub API base URL (supports GitHub Enterprise) */
    String githubApiUrl = 'https://api.github.com'

    /** Labels to apply to created issues */
    List<String> labels = ['security', 'automated']

    /** Assignees for created issues */
    List<String> assignees = []

    /** Maximum number of retry attempts on transient/rate-limit errors */
    int maxRetries = 5

    /** Minimum milliseconds between POST/PATCH calls (secondary rate limit protection) */
    int minCallGapMs = 1000

    /** Minimum remaining API calls before self-throttling */
    int rateLimitThreshold = 100

    /** Optional webhook URL for alerts on safe-fail */
    String alertWebhookUrl = null

    /** Maximum wait time in ms before giving up on rate limit reset (5 minutes) */
    long maxWaitMs = 300_000L

    /**
     * Validate required configuration fields.
     * @throws IllegalArgumentException if required fields are missing
     */
    void validate() {
        if (!githubRepo?.trim()) {
            throw new IllegalArgumentException("githubRepo is required (format: 'owner/repo')")
        }
        if (!githubRepo.contains('/')) {
            throw new IllegalArgumentException("githubRepo must be in 'owner/repo' format")
        }
        if (!credentialId?.trim()) {
            throw new IllegalArgumentException("credentialId is required")
        }
        if (maxRetries < 0 || maxRetries > 20) {
            throw new IllegalArgumentException("maxRetries must be between 0 and 20")
        }
        if (minCallGapMs < 0) {
            throw new IllegalArgumentException("minCallGapMs must be non-negative")
        }
    }
}