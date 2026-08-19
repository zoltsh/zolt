package sh.zolt.manifest;

import java.util.Objects;
import java.util.Optional;

/** Enabled canonical or replacement Central repository, or an explicitly disabled Central. */
public record EffectiveCentralRepository(Optional<DependencyRepository> repository) {
    public EffectiveCentralRepository {
        repository = Objects.requireNonNull(
                repository, "Effective Central repository must not be null.");
    }

    public static EffectiveCentralRepository enabled(DependencyRepository repository) {
        return new EffectiveCentralRepository(Optional.of(
                Objects.requireNonNull(repository, "Effective Central repository must not be null.")));
    }

    public static EffectiveCentralRepository disabled() {
        return new EffectiveCentralRepository(Optional.empty());
    }

    public boolean enabled() {
        return repository.isPresent();
    }
}
