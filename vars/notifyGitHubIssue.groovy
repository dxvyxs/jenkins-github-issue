import org.jenkins.plugins.github_issue_creator.*

/**
 * Jenkins Pipeline shared library step: notifyGitHubIssue
 *
 * Usage in Declarative Pipeline:
 *   post {
 *       failure {
 *           notifyGitHubIssue(
 *               githubRepo: 'owner/repo',
 *               credentialId: 'github-app-cred',
 *               labels: ['ci-failure', 'automated'],
 *               stageName: 'Build'
 *           )
 *       }
 *   }
 *
 * Usage in Scripted Pipeline:
 *   catchError {
 *       // build steps
 *   }
 *   if (currentBuild.result == 'FAILURE') {
 *       notifyGitHubIssue(githubRepo: 'owner/repo', credentialId: 'github-app-cred')
 *   }
 *
 * Parameters:
 *   githubRepo       - (required) GitHub repository in "owner/repo" format
 *   credentialId     - (required) Jenkins credential ID for GitHub token
 *   githubApiUrl     - GitHub API base URL (default: https://api.github.com)
 *   labels           - List of labels (default: ['ci-failure', 'automated'])
 *   assignees        - List of assignees (default: [])
 *   stageName        - Stage name override (default: auto-detected or 'Unknown')
 *   maxRetries       - Max retry attempts (default: 5)
 *   minCallGapMs     - Min ms between mutating calls (default: 1000)
 *   rateLimitThreshold - Min remaining calls before self-throttle (default: 100)
 *   alertWebhookUrl  - Slack/Teams webhook for alerts (optional)
 *   customFailureKey - Override automatic failure signature (optional)
 *   maxLogLines      - Max log lines in issue body (default: 50)
 */
def call(Map params = [:]) {
    // Validate required parameters early
    if (!params.githubRepo) {
        error "[notifyGitHubIssue] 'githubRepo' parameter is required (format: 'owner/repo')"
    }
    if (!params.credentialId) {
        error "[notifyGitHubIssue] 'credentialId' parameter is required"
    }

    // Build configuration
    Config config = new Config(
        githubRepo: params.githubRepo,
        credentialId: params.credentialId,
        githubApiUrl: params.githubApiUrl ?: 'https://api.github.com',
        labels: params.labels ?: ['ci-failure', 'automated'],
        assignees: params.assignees ?: [],
        maxRetries: params.maxRetries ?: 5,
        minCallGapMs: params.minCallGapMs ?: 1000,
        rateLimitThreshold: params.rateLimitThreshold ?: 100,
        alertWebhookUrl: params.alertWebhookUrl,
        customFailureKey: params.customFailureKey,
        maxLogLines: params.maxLogLines ?: 50
    )

    // Gather failure context from the Jenkins environment
    FailureContext failureContext = buildFailureContext(params)

    // Execute with credential binding (token never touches the build log)
    withCredentials([string(credentialsId: config.credentialId, variable: 'GITHUB_TOKEN')]) {
        SecureToken token = null
        try {
            // Wrap token securely
            token = new SecureToken(env.GITHUB_TOKEN)

            // Mask the token in build output (defense in depth)
            // The withCredentials block already masks, but this is a safety net
            echo "[notifyGitHubIssue] Processing failure for ${failureContext.jobName} / ${failureContext.stageName}"

            // Build components
            RateLimitManager rateLimitManager = new RateLimitManager(
                config.minCallGapMs,
                config.rateLimitThreshold
            )

            // Check Jenkins-level GitHub API throttling config
            applyJenkinsRateLimitStrategy(rateLimitManager, config)

            GitHubApiClient apiClient = new GitHubApiClient(
                config.githubApiUrl,
                token,
                { String msg -> echo "[GitHubAPI] ${msg}" }
            )

            RetryExecutor retryExecutor = new RetryExecutor(
                rateLimitManager,
                config.maxRetries,
                config.maxWaitMs,
                null, // use real Thread.sleep
                null, // use real System.currentTimeMillis
                { String msg -> echo "[Retry] ${msg}" }
            )

            AlertNotifier alertNotifier = new AlertNotifier(
                config.alertWebhookUrl,
                { String msg -> echo "[Alert] ${msg}" }
            )

            GitHubIssueCreator creator = new GitHubIssueCreator(
                config,
                apiClient,
                rateLimitManager,
                retryExecutor,
                alertNotifier,
                { String msg -> echo "[IssueCreator] ${msg}" }
            )

            // Execute the main logic
            CreationResult result = creator.createOrUpdateIssue(failureContext)

            // Report result
            switch (result.action) {
                case CreationAction.CREATED:
                    echo "[notifyGitHubIssue] Created issue #${result.issueNumber}"
                    break
                case CreationAction.COMMENTED:
                    echo "[notifyGitHubIssue] Commented on existing issue #${result.issueNumber}"
                    break
                case CreationAction.REOPENED:
                    echo "[notifyGitHubIssue] Reopened issue #${result.issueNumber}"
                    break
                case CreationAction.SKIPPED_CACHED:
                    echo "[notifyGitHubIssue] Skipped (already handled this build): issue #${result.issueNumber}"
                    break
                case CreationAction.SAFE_FAIL:
                    echo "[notifyGitHubIssue] WARNING: Could not create/update issue: ${result.failReason}"
                    // Do NOT fail the pipeline — this is a best-effort notification
                    break
            }

            return result

        } catch (Exception e) {
            // Catch-all: never let the issue creator crash the pipeline
            String safeMessage = sanitizeExceptionMessage(e.message)
            echo "[notifyGitHubIssue] ERROR: Unexpected failure (pipeline will continue): ${safeMessage}"
            return new CreationResult(
                action: CreationAction.SAFE_FAIL,
                identityHash: 'unknown',
                failReason: safeMessage
            )
        } finally {
            // Zero out the token in memory
            token?.destroy()
        }
    }
}

/**
 * Build the FailureContext from Jenkins pipeline environment.
 */
private FailureContext buildFailureContext(Map params) {
    String jobName = env.JOB_NAME ?: 'unknown-job'
    String stageName = params.stageName ?: env.STAGE_NAME ?: 'Unknown'
    int buildNumber = env.BUILD_NUMBER?.isInteger() ? env.BUILD_NUMBER.toInteger() : 0
    String buildUrl = env.BUILD_URL ?: ''

    // Extract failure log from the current build
    String failureLog = getFailureLog()

    return new FailureContext(
        jobName: jobName,
        stageName: stageName,
        failureLog: failureLog,
        buildNumber: buildNumber,
        buildUrl: buildUrl
    )
}

/**
 * Extract the failure log from the current build.
 * Uses the last 200 lines of the build log as the failure context.
 */
private String getFailureLog() {
    try {
        // Access the build log through the currentBuild object
        String fullLog = currentBuild.rawBuild?.getLog(200)?.join('\n') ?: ''
        if (!fullLog) {
            // Fallback: try to get log via currentBuild
            fullLog = currentBuild.rawBuild?.getLog()?.takeRight(200)?.join('\n') ?: '<log unavailable>'
        }
        return fullLog
    } catch (Exception e) {
        // If we can't access the log (sandbox restrictions), return what we can
        return "<log access restricted: ${e.message}>"
    }
}

/**
 * Read Jenkins' GitHub API rate-limit strategy and apply it.
 */
private void applyJenkinsRateLimitStrategy(RateLimitManager rateLimitManager, Config config) {
    try {
        // Access Jenkins' GitHub plugin configuration
        // This reads the ApiRateLimitChecker setting from Manage Jenkins → GitHub API usage
        def jenkins = Jenkins.instance
        if (jenkins == null) {
            echo "[notifyGitHubIssue] Not running in Jenkins context, skipping rate-limit strategy check"
            return
        }

        def githubPluginConfig = jenkins.getDescriptorByType(
            Class.forName('org.jenkinsci.plugins.github.config.GitHubPluginConfig')
        )

        if (githubPluginConfig != null) {
            def checker = githubPluginConfig.getRateLimitChecker()
            String strategyName = checker?.getClass()?.simpleName ?: 'Unknown'
            echo "[notifyGitHubIssue] Jenkins GitHub API strategy: ${strategyName}"
            rateLimitManager.applyJenkinsStrategy(strategyName, config.rateLimitThreshold)
        }
    } catch (Exception e) {
        // If we can't read the strategy, proceed with defaults
        echo "[notifyGitHubIssue] Could not read Jenkins rate-limit strategy (using defaults): ${e.message}"
    }
}

/**
 * Remove potential token values from exception messages.
 */
private static String sanitizeExceptionMessage(String message) {
    if (!message) return '<no message>'
    return message
        .replaceAll(/(?i)(bearer\s+)\S+/, '$1***REDACTED***')
        .replaceAll(/ghp_[A-Za-z0-9_]+/, '***REDACTED***')
        .replaceAll(/ghs_[A-Za-z0-9_]+/, '***REDACTED***')
        .replaceAll(/github_pat_[A-Za-z0-9_]+/, '***REDACTED***')
}
