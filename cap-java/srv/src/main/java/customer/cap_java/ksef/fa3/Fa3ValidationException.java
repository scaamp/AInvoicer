package customer.cap_java.ksef.fa3;

import java.util.List;

/** Dane lub XML nie spełniają reguł FA(3); dokument nie może zostać zarejestrowany ani wysłany. */
public class Fa3ValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient List<Fa3Violation> violations;

    public Fa3ValidationException(List<Fa3Violation> violations) {
        super("FA3_INVALID: " + violations);
        this.violations = List.copyOf(violations);
    }

    public List<Fa3Violation> violations() {
        return violations;
    }

    /** Czy wśród naruszeń jest dany kod — skrót dla wywołujących i testów. */
    public boolean has(String code) {
        return violations.stream().anyMatch(v -> v.code().equals(code));
    }
}
