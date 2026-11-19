package android.database;

/**
 * The framework class as it really is, for the host JVM. The mockable android.jar's stand-in drops the
 * message, and Room's {@code @Upsert} reads the message to tell a key conflict (update instead) from any
 * other failure — so without this, every upsert of an existing row would fail in a unit test only.
 */
public class SQLException extends RuntimeException {
    public SQLException() {
        super();
    }

    public SQLException(String error) {
        super(error);
    }

    public SQLException(String error, Throwable cause) {
        super(error, cause);
    }
}
