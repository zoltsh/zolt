package sh.zolt.toml;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Deterministic source-ordered shape failures. */
final class ManifestShapeDiagnostics {
    private final List<Violation> violations = new ArrayList<>();
    private int sequence;

    void add(ManifestShapeSource source, String message) {
        add(source.span().start(), message);
    }

    void add(SourceSpan span, String message) {
        add(span.start(), message);
    }

    void add(int offset, String message) {
        violations.add(new Violation(Math.max(0, offset), sequence++, message));
    }

    boolean hasViolationAt(int offset) {
        return violations.stream().anyMatch(value -> value.offset() == offset);
    }

    void throwIfAny() {
        violations.stream()
                .min(Comparator.comparingInt(Violation::offset)
                        .thenComparingInt(Violation::sequence))
                .ifPresent(violation -> {
                    throw new ZoltConfigException(violation.message());
                });
    }

    private record Violation(int offset, int sequence, String message) {
    }
}
