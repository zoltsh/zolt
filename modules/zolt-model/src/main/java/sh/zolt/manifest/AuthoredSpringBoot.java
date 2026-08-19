package sh.zolt.manifest;

import java.util.Objects;
import java.util.Optional;

/** Authored fields in the optional {@code [framework.spring-boot]} table. */
public record AuthoredSpringBoot(Optional<Boolean> nativeImage) {
    public AuthoredSpringBoot {
        nativeImage = Objects.requireNonNull(
                nativeImage, "Authored Spring Boot native value must not be null.");
        if (nativeImage.isEmpty()) {
            throw new IllegalArgumentException(
                    "Authored Spring Boot settings must contain the `native` field.");
        }
    }
}
