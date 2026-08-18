package org.jenkins.plugins.github_issue_creator

/**
 * Secure wrapper for authentication tokens.
 * Ensures the token value never appears in toString(), logs, or exception messages.
 * Uses char[] internally so it can be explicitly zeroed after use.
 */
class SecureToken implements Serializable {
    private static final long serialVersionUID = 1L

    private char[] value

    SecureToken(String token) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("Token must not be null or empty")
        }
        this.value = token.toCharArray()
    }

    /**
     * Returns the Authorization header value.
     * This is the ONLY way to access the token content.
     */
    String getHeaderValue() {
        if (value == null) {
            throw new IllegalStateException("Token has been destroyed")
        }
        return "Bearer " + new String(value)
    }

    /**
     * Returns the raw token value (for credential registration with log masking).
     * Use sparingly — only for registering with MaskPasswordsBuildWrapper.
     */
    String getRawValue() {
        if (value == null) {
            throw new IllegalStateException("Token has been destroyed")
        }
        return new String(value)
    }

    /**
     * Zeros out the token in memory. Call in finally blocks after use.
     */
    void destroy() {
        if (value != null) {
            Arrays.fill(value, '\0' as char)
            value = null
        }
    }

    /**
     * Never expose the token in string representations.
     */
    @Override
    String toString() {
        return "***REDACTED***"
    }

    /**
     * Prevent serialization of the actual token value in debug output.
     */
    String inspect() {
        return "SecureToken[REDACTED]"
    }
}
