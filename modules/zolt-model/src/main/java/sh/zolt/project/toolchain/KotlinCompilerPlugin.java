package sh.zolt.project.toolchain;

import java.util.Optional;

/** Closed, version-aligned Kotlin compiler plugins owned by Zolt. */
public enum KotlinCompilerPlugin {
    SERIALIZATION("serialization"),
    SPRING("spring"),
    MICRONAUT("micronaut"),
    JPA("jpa"),
    POWER_ASSERT("power-assert");

    private final String id;

    KotlinCompilerPlugin(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Optional<KotlinCompilerPlugin> fromId(String value) {
        for (KotlinCompilerPlugin plugin : values()) {
            if (plugin.id.equals(value)) {
                return Optional.of(plugin);
            }
        }
        return Optional.empty();
    }
}
