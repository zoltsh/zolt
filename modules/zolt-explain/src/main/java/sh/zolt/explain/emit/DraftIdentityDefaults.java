package sh.zolt.explain.emit;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The {@code [workspace.project]} identity a drafted workspace root supplies to its members.
 *
 * <p>A member never materializes a value it inherits (design §4.3, §5.1), so each accessor answers
 * one question: what should this member author? A value equal to the workspace default, or a value
 * the audit could not read while the workspace supplies one, is inherited and returns empty. Only
 * when nothing is inherited and nothing was read does a placeholder become necessary.
 *
 * <p>{@link #none()} is the standalone case, where every value the audit read is authored locally.
 */
record DraftIdentityDefaults(
        Optional<String> group,
        Optional<String> version,
        Optional<Integer> javaRelease) {
    private static final DraftIdentityDefaults NONE =
            new DraftIdentityDefaults(Optional.empty(), Optional.empty(), Optional.empty());

    static DraftIdentityDefaults none() {
        return NONE;
    }

    boolean isEmpty() {
        return group.isEmpty() && version.isEmpty() && javaRelease.isEmpty();
    }

    Optional<String> group(String inspected, Supplier<String> placeholder) {
        return authored(group, inspected, placeholder);
    }

    Optional<String> version(String inspected, Supplier<String> placeholder) {
        return authored(version, inspected, placeholder);
    }

    Optional<Integer> javaRelease(Optional<Integer> inspected, Runnable unreadable) {
        if (inspected.isPresent()) {
            return javaRelease.filter(inspected.get()::equals).isPresent()
                    ? Optional.empty()
                    : inspected;
        }
        if (javaRelease.isPresent()) {
            return Optional.empty();
        }
        unreadable.run();
        return Optional.empty();
    }

    private static Optional<String> authored(
            Optional<String> shared, String inspected, Supplier<String> placeholder) {
        if (inspected != null && !inspected.isBlank()) {
            return shared.filter(inspected::equals).isPresent()
                    ? Optional.empty()
                    : Optional.of(inspected);
        }
        if (shared.isPresent()) {
            return Optional.empty();
        }
        return Optional.of(placeholder.get());
    }
}
