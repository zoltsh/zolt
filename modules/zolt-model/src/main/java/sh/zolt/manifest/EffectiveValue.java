package sh.zolt.manifest;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** One effective value together with its authored, inherited, or built-in provenance. */
public record EffectiveValue<T>(T value, ValueOrigin origin, Optional<ManifestSource> source) {
    public EffectiveValue {
        Objects.requireNonNull(value, "Effective value must not be null.");
        Objects.requireNonNull(origin, "Effective value origin must not be null.");
        Objects.requireNonNull(source, "Effective value source must not be null.");
        if (origin == ValueOrigin.BUILT_IN && source.isPresent()) {
            throw new IllegalArgumentException("A built-in value cannot have an authored manifest source.");
        }
        if (origin != ValueOrigin.BUILT_IN && source.isEmpty()) {
            throw new IllegalArgumentException("An authored or inherited value requires a manifest source.");
        }
    }

    public static <T> EffectiveValue<T> authored(T value, ManifestSource source) {
        return new EffectiveValue<>(value, ValueOrigin.AUTHORED, Optional.of(source));
    }

    public static <T> EffectiveValue<T> inherited(T value, ManifestSource source) {
        return new EffectiveValue<>(value, ValueOrigin.INHERITED, Optional.of(source));
    }

    public static <T> EffectiveValue<T> builtIn(T value) {
        return new EffectiveValue<>(value, ValueOrigin.BUILT_IN, Optional.empty());
    }

    public <R> EffectiveValue<R> map(Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "Effective value mapper must not be null.");
        return new EffectiveValue<>(mapper.apply(value), origin, source);
    }
}
