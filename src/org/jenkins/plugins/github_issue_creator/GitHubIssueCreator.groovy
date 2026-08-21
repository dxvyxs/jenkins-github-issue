package org.jenkins.plugins.github_issue_creator

import java.util.concurrent.locks.ReentrantLock

/**
 * Orchestrator: ties together all components to create/update GitHub issues on build failure.
 *
 * Flow:
 * 1. Compute deterministic failure identity
 * 2. Check Jenkins rate budget
 * 3. Search for existing issue with matching marker
 * 4. Create new issue, or comment on / reopen existing issue
 * 5. Handle safe-fail path on any error
 *
 * Thread-safety: All GitHub API calls are serialized via a static lock.
 * Idempotent: Safe to call multiple times for the same failure.
 */
class GitHubIssueCreator implements Serializable {
    private static final long serialVersionUID = 1L

    /** Static lock ensures only one GitHub API call at a time across all pipeline runs on this controller */
    private static final ReentrantLock API_LOCK = new ReentrantLock()

    private final Config config
    private final GitHubApiClient apiClient
    private final RateLimitManager rateLimitManager
    private final RetryExecutor retryExecutor
    private final AlertNotifier alertNotifier
    private final Closure<Void> logger

    /** In-memory dedup cache for the lifetime of this instance (same build) */
    private final Map<String, Integer> localDedupCache = [:]

    GitHubIssueCreator(Config config, GitHubApiClient apiClient,
                       RateLimitManager rateLimitManager, RetryExecutor retryExecutor,
                       AlertNotifier alertNotifier, Closure<Void> logger = null) {
        this.config = config
        this.apiClient = apiClient
        this.rateLimitManager = rateLimitManager
        this.retryExecutor = retryExecutor
        this.alertNotifier = alertNotifier
        this.logger = logger ?: { String msg -> println("[GitHubIssueCreator] ${msg}") }
    }

    /**
     * Main entry point: create or update a GitHub issue for a build failure.
     *
     * @param failureContext Context about the failure (job, stage, log, build info)
     * @return CreationResult indicating what action was taken
     */
    CreationResult createOrUpdateIssue(FailureContext failureContext) {
        config.validate()

        // 1. Compute failure identity
        FailureIdentity identity = FailureIdentityEngine.computeSarifFinding(
            failureContext.jobName,
            failureContext.sarifResult
        )
        logger.call("Computed failure identity: ${identity.hash}")

        // 2. Check local dedup cache first (handles same-build rapid re-runs)
        if (localDedupCache.containsKey(identity.hash)) {
            int cachedIssueNumber = localDedupCache[identity.hash]
            logger.call("Dedup cache hit: issue #${cachedIssueNumber} already handled this build")
            return new CreationResult(
                action: CreationAction.SKIPPED_CACHED,
                issueNumber: cachedIssueNumber,
                identityHash: identity.hash
            )
        }

        // 3. Acquire lock for serialized API access
        API_LOCK.lock()
        try {
            return executeWithLock(identity, failureContext)
        } finally {
            API_LOCK.unlock()
        }
    }

    private CreationResult executeWithLock(FailureIdentity identity, FailureContext failureContext) {
        // 4. Search for existing issue
        ExecutionResult searchResult = retryExecutor.execute(false) {
            apiClient.searchIssues(config.githubRepo, "\"${identity.marker}\" in:body")
        }

        if (searchResult.isSafeFail()) {
            handleSafeFail(searchResult as ExecutionResult.SafeFail, failureContext)
            return new CreationResult(
                action: CreationAction.SAFE_FAIL,
                identityHash: identity.hash,
                failReason: (searchResult as ExecutionResult.SafeFail).reason
            )
        }

        // 5. Parse search results and find matching issue
        Map existingIssue = findIssueWithMarker(
            (searchResult as ExecutionResult.Success).response,
            identity.hash
        )

        // 6. Decide action based on existing issue state
        if (existingIssue == null) {
            return createNewIssue(identity, failureContext)
        } else if (existingIssue.state == 'closed') {
            return reopenAndComment(existingIssue, identity, failureContext)
        } else {
            return commentOnExisting(existingIssue, identity, failureContext)
        }
    }

    /**
     * Create a brand new issue.
     */
    private CreationResult createNewIssue(FailureIdentity identity, FailureContext failureContext) {
        String title = buildIssueTitle(failureContext)
        String body = buildIssueBody(failureContext, identity.marker)

        ExecutionResult result = retryExecutor.execute(true) {
            apiClient.createIssue(config.githubRepo, title, body, config.labels, config.assignees)
        }

        if (result.isSafeFail()) {
            handleSafeFail(result as ExecutionResult.SafeFail, failureContext)
            return new CreationResult(
                action: CreationAction.SAFE_FAIL,
                identityHash: identity.hash,
                failReason: (result as ExecutionResult.SafeFail).reason
            )
        }

        ApiResponse response = (result as ExecutionResult.Success).response
        Object parsed = response.parsedBody()
        int issueNumber = parsed?.number ?: 0

        localDedupCache[identity.hash] = issueNumber
        logger.call("Created issue #${issueNumber} for identity ${identity.hash}")

        return new CreationResult(
            action: CreationAction.CREATED,
            issueNumber: issueNumber,
            identityHash: identity.hash
        )
    }

    /**
     * Reopen a closed issue and add a comment.
     */
    private CreationResult reopenAndComment(Map existingIssue, FailureIdentity identity, FailureContext failureContext) {
        int issueNumber = existingIssue.number as int

        // Reopen
        ExecutionResult reopenResult = retryExecutor.execute(true) {
            apiClient.reopenIssue(config.githubRepo, issueNumber)
        }

        if (reopenResult.isSafeFail()) {
            handleSafeFail(reopenResult as ExecutionResult.SafeFail, failureContext)
            return new CreationResult(
                action: CreationAction.SAFE_FAIL,
                issueNumber: issueNumber,
                identityHash: identity.hash,
                failReason: (reopenResult as ExecutionResult.SafeFail).reason
            )
        }

        // Add comment
        String commentBody = buildCommentBody(failureContext)
        ExecutionResult commentResult = retryExecutor.execute(true) {
            apiClient.addComment(config.githubRepo, issueNumber, commentBody)
        }

        if (commentResult.isSafeFail()) {
            // Issue was reopened but comment failed — not ideal but not catastrophic
            logger.call("WARNING: Issue #${issueNumber} reopened but comment failed: ${(commentResult as ExecutionResult.SafeFail).reason}")
        }

        localDedupCache[identity.hash] = issueNumber
        logger.call("Reopened and commented on issue #${issueNumber} for identity ${identity.hash}")

        return new CreationResult(
            action: CreationAction.REOPENED,
            issueNumber: issueNumber,
            identityHash: identity.hash
        )
    }

    /**
     * Add a comment to an existing open issue.
     */
    private CreationResult commentOnExisting(Map existingIssue, FailureIdentity identity, FailureContext failureContext) {
        int issueNumber = existingIssue.number as int
        String commentBody = buildCommentBody(failureContext)

        ExecutionResult result = retryExecutor.execute(true) {
            apiClient.addComment(config.githubRepo, issueNumber, commentBody)
        }

        if (result.isSafeFail()) {
            handleSafeFail(result as ExecutionResult.SafeFail, failureContext)
            return new CreationResult(
                action: CreationAction.SAFE_FAIL,
                issueNumber: issueNumber,
                identityHash: identity.hash,
                failReason: (result as ExecutionResult.SafeFail).reason
            )
        }

        localDedupCache[identity.hash] = issueNumber
        logger.call("Commented on existing issue #${issueNumber} for identity ${identity.hash}")

        return new CreationResult(
            action: CreationAction.COMMENTED,
            issueNumber: issueNumber,
            identityHash: identity.hash
        )
    }

    /**
     * Find an issue in search results that contains our marker.
     */
    private Map findIssueWithMarker(ApiResponse searchResponse, String identityHash) {
        if (!searchResponse?.body) return null

        try {
            Object parsed = searchResponse.parsedBody()
            List items = parsed?.items ?: []

            for (Map item : items) {
                String body = item.body ?: ''
                String foundHash = FailureIdentityEngine.extractMarkerHash(body)
                if (foundHash == identityHash) {
                    return item
                }
            }
        } catch (Exception e) {
            logger.call("WARNING: Failed to parse search results: ${e.message}")
        }

        return null
    }

    /**
     * Handle a safe-fail: log and optionally alert.
     */
    private void handleSafeFail(ExecutionResult.SafeFail safeFail, FailureContext failureContext) {
        logger.call("ERROR: Safe-fail triggered: ${safeFail.reason}")

        if (safeFail.shouldAlert) {
            alertNotifier.alert(safeFail.reason, [
                job: failureContext.jobName,
                stage: failureContext.stageName,
                build: "#${failureContext.buildNumber}",
                buildUrl: failureContext.buildUrl
            ])
        }
    }

    // --- Template builders ---

    private String buildIssueTitle(FailureContext ctx) {
        String cve = FailureIdentityEngine.extractCve(ctx.sarifResult)
        return "[Security] ${cve}"
    }

    private String buildIssueBody(FailureContext ctx, String marker) {
        String cve = FailureIdentityEngine.extractCve(ctx.sarifResult)
        String location = FailureIdentityEngine.extractLocation(ctx.sarifResult)
        String ruleId = FailureIdentityEngine.extractRuleId(ctx.sarifResult)

        return """\
    ## Security Finding

    | Field | Value |
    |-------|-------|
    | CVE | ${cve} |
    | Scanner | ${ctx.toolName} |
    | Rule | ${ruleId} |
    | Location | ${location} |
    | Job | ${ctx.jobName} |
    | Build | #${ctx.buildNumber} |
    | Build URL | ${ctx.buildUrl} |

    ### Finding

    ${ctx.sarifResult?.message?.text ?: 'No finding message provided.'}

    ---

    *This issue was automatically created by the Jenkins GitHub Issue Creator.*

    ${marker}"""
    }

    private String buildCommentBody(FailureContext ctx) {
        String cve = FailureIdentityEngine.extractCve(ctx.sarifResult)
        String location = FailureIdentityEngine.extractLocation(ctx.sarifResult)
        String ruleId = FailureIdentityEngine.extractRuleId(ctx.sarifResult)

        return """\
    ## Same CVE Detected Again

    | Field | Value |
    |-------|-------|
    | CVE | ${cve} |
    | Scanner | ${ctx.toolName} |
    | Rule | ${ruleId} |
    | Location | ${location} |
    | Build | #${ctx.buildNumber} |
    | Build URL | ${ctx.buildUrl} |
    | Timestamp | ${new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))} |

    ### Finding

    ${ctx.sarifResult?.message?.text ?: 'No finding message provided.'}

    ---

    *Automated comment added by Jenkins GitHub Issue Creator.*"""
    }
}

/**
 * Context about a build failure, passed from the Jenkins pipeline.
 */
