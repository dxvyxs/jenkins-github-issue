package org.jenkins.plugins.github_issue_creator

import groovy.json.JsonOutput

/**
 * Sends alert notifications on safe-fail scenarios.
 * Supports webhook (Slack/Teams) notification.
 * Falls back to logging if no webhook is configured.
 */
class AlertNotifier implements Serializable {
    private static final long serialVersionUID = 1L

    private final String webhookUrl
    private final Closure<Void> logger

    /** Injectable HTTP poster for testing */
    private final Closure<Integer> httpPoster

    AlertNotifier(String webhookUrl = null, Closure<Void> logger = null, Closure<Integer> httpPoster = null) {
        this.webhookUrl = webhookUrl
        this.logger = logger ?: { String msg -> println("[AlertNotifier] ${msg}") }
        this.httpPoster = httpPoster
    }

    /**
     * Send an alert about a safe-fail scenario.
     *
     * @param reason Description of why the operation failed
     * @param context Additional context (job name, build URL, etc.)
     */
    void alert(String reason, Map<String, String> context = [:]) {
        String message = buildAlertMessage(reason, context)
        logger.call("ALERT: ${message}")

        if (!webhookUrl?.trim()) {
            logger.call("No webhook configured, alert logged only")
            return
        }

        try {
            postToWebhook(message)
        } catch (Exception e) {
            // Alert failure should never crash the pipeline
            logger.call("WARNING: Failed to send alert webhook: ${e.message}")
        }
    }

    private String buildAlertMessage(String reason, Map<String, String> context) {
        StringBuilder sb = new StringBuilder()
        sb.append("[Jenkins GitHub Issue Creator] Safe-fail triggered\n")
        sb.append("Reason: ${reason}\n")
        if (context) {
            context.each { k, v ->
                sb.append("${k}: ${v}\n")
            }
        }
        return sb.toString()
    }

    private void postToWebhook(String message) {
        // Slack-compatible webhook payload
        String payload = JsonOutput.toJson([text: message])

        if (httpPoster != null) {
            httpPoster.call(webhookUrl, payload)
            return
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(webhookUrl).openConnection()
        conn.setRequestMethod('POST')
        conn.setRequestProperty('Content-Type', 'application/json')
        conn.setConnectTimeout(10_000)
        conn.setReadTimeout(10_000)
        conn.setDoOutput(true)
        conn.outputStream.withWriter('UTF-8') { writer ->
            writer.write(payload)
        }

        int responseCode = conn.responseCode
        if (responseCode >= 400) {
            logger.call("Webhook returned HTTP ${responseCode}")
        }
    }
}
