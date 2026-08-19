package sh.zolt.manifest;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** The explicitly present {@code [repositories]} control table. */
public record AuthoredRepositoryControl(
        Optional<CentralRepositoryControl> central,
        Optional<List<LocalId>> order) {
    public AuthoredRepositoryControl {
        central = Objects.requireNonNull(central, "Authored Central control must not be null.");
        order = Objects.requireNonNull(order, "Authored repository order must not be null.")
                .map(AuthoredRepositoryControl::immutableUniqueOrder);
        if (central.isEmpty() && order.isEmpty()) {
            throw new IllegalArgumentException("An explicitly authored [repositories] table must not be empty.");
        }
    }

    private static List<LocalId> immutableUniqueOrder(List<LocalId> values) {
        Objects.requireNonNull(values, "Authored repository order must not be null.");
        List<LocalId> copy = List.copyOf(values);
        Set<LocalId> seen = new HashSet<>();
        for (LocalId id : copy) {
            if (!seen.add(id)) {
                throw new IllegalArgumentException("Repository order lists `" + id + "` more than once.");
            }
        }
        return copy;
    }
}
