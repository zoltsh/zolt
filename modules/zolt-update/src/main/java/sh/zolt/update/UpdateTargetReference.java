package sh.zolt.update;

import java.util.List;
import java.util.Objects;

/** Raw catalog view used where public schema-v2 target identity is neither requested nor required. */
public record UpdateTargetReference(UpdateTargetKey key, List<String> governs) {
    public UpdateTargetReference {
        key = Objects.requireNonNull(key, "key");
        governs = governs == null ? List.of() : List.copyOf(governs);
    }

    public OutdatedSurface surface() {
        return key.surface();
    }

    public String identifier() {
        return key.identifier();
    }
}
