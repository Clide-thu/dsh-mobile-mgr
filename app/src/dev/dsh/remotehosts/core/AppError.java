package dev.dsh.remotehosts.core;

/**
 * An error with a machine-readable {@link ErrorKind}.
 *
 * Every constructor must receive an already-sanitised message: secrets are
 * scrubbed where they are known (engine / store), never here, because this class
 * cannot know which host a message belongs to.
 */
public class AppError extends Exception {

    public final ErrorKind kind;

    public AppError(ErrorKind kind, String message) {
        super(message == null ? "" : message);
        this.kind = kind;
    }

    public AppError(ErrorKind kind, String message, Throwable cause) {
        super(message == null ? "" : message, cause);
        this.kind = kind;
    }

    public static AppError badRequest(String message) {
        return new AppError(ErrorKind.BAD_REQUEST, message);
    }

    public static AppError unknownHost(String message) {
        return new AppError(ErrorKind.UNKNOWN_HOST, message);
    }

    public static AppError needsCredentials(String message) {
        return new AppError(ErrorKind.NEEDS_CREDENTIALS, message);
    }

    public static AppError unreachable(String message) {
        return new AppError(ErrorKind.UNREACHABLE, message);
    }

    public static AppError hostKeyChanged(String message) {
        return new AppError(ErrorKind.HOST_KEY_CHANGED, message);
    }

    public static AppError internal(String message) {
        return new AppError(ErrorKind.INTERNAL, message);
    }
}
