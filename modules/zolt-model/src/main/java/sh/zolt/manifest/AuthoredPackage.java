package sh.zolt.manifest;

import java.util.Objects;
import java.util.Optional;

/** Authored {@code [package]} settings without materializing package defaults. */
public record AuthoredPackage(
        Optional<Mode> mode,
        Optional<Boolean> sources,
        Optional<Boolean> javadoc,
        Optional<Boolean> testJar,
        Optional<DuplicatePolicy> duplicates) {
    public AuthoredPackage {
        mode = Objects.requireNonNull(mode, "Authored package mode must not be null.");
        sources = Objects.requireNonNull(sources, "Authored sources package flag must not be null.");
        javadoc = Objects.requireNonNull(javadoc, "Authored javadoc package flag must not be null.");
        testJar = Objects.requireNonNull(testJar, "Authored test JAR package flag must not be null.");
        duplicates = Objects.requireNonNull(
                duplicates, "Authored package duplicate policy must not be null.");
        if (mode.isEmpty()
                && sources.isEmpty()
                && javadoc.isEmpty()
                && testJar.isEmpty()
                && duplicates.isEmpty()) {
            throw new IllegalArgumentException("Authored package settings must not be empty.");
        }
        if (duplicates.isPresent() && mode.orElse(Mode.JAR) != Mode.UBER_JAR) {
            throw new IllegalArgumentException(
                    "Package duplicates are valid only with mode `uber-jar`.");
        }
    }

    /** Package modes accepted in authored source; BOM packaging is represented by {@code [bom]}. */
    public enum Mode {
        JAR("jar"),
        UBER_JAR("uber-jar"),
        WAR("war"),
        SPRING_BOOT("spring-boot"),
        SPRING_BOOT_WAR("spring-boot-war"),
        QUARKUS("quarkus");

        private final String configValue;

        Mode(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }

        public static Mode fromConfigValue(String value) {
            Objects.requireNonNull(value, "Authored package mode must not be null.");
            for (Mode candidate : values()) {
                if (candidate.configValue.equals(value)) {
                    return candidate;
                }
            }
            if (value.equals("bom")) {
                throw new IllegalArgumentException(
                        "Authored package mode `bom` is invalid; a [bom] domain implies BOM packaging.");
            }
            throw new IllegalArgumentException("Unsupported authored package mode `" + value + "`.");
        }
    }

    /** Duplicate handling available only to an authored uber JAR. */
    public enum DuplicatePolicy {
        FAIL("fail"),
        FIRST_WINS("first-wins");

        private final String configValue;

        DuplicatePolicy(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }

        public static DuplicatePolicy fromConfigValue(String value) {
            Objects.requireNonNull(value, "Authored package duplicate policy must not be null.");
            for (DuplicatePolicy candidate : values()) {
                if (candidate.configValue.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException(
                    "Unsupported authored package duplicate policy `" + value + "`.");
        }
    }
}
