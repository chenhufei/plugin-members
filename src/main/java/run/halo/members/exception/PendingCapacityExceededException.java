package run.halo.members.exception;

/**
 * Raised when the configured number of pending member applications has been reached.
 */
public class PendingCapacityExceededException extends RuntimeException {

    public PendingCapacityExceededException(String message) {
        super(message);
    }
}
