package cobdev.bridge;

/** A request the game declines on purpose; the agent maps any exception named Refused to HTTP 409. */
public final class Refused extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public Refused(String message) {
        super(message);
    }

    static void check(String policyResult) {
        if (policyResult != null) throw new Refused(policyResult);
    }
}
