package com.macmario.services.comm.teams;

/**
 * Unchecked exception for all failures raised by the comm.teams library.
 *
 * <p>It is intentionally unchecked so it composes cleanly inside a web
 * application's request handling without forcing {@code throws} clauses through
 * every layer. When the failure originated from an HTTP call, {@link #status()}
 * carries the HTTP status code (otherwise {@code -1}).
 */
public class TeamsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** HTTP status code when the failure came from an HTTP response, else {@code -1}. */
    private final int status;

    public TeamsException(String message) {
        this(message, -1, null);
    }

    public TeamsException(String message, Throwable cause) {
        this(message, -1, cause);
    }

    public TeamsException(String message, int status) {
        this(message, status, null);
    }

    public TeamsException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /**
     * @return the HTTP status that triggered this failure, or {@code -1} when the
     *         failure was not HTTP related.
     */
    public int status() {
        return status;
    }
}
