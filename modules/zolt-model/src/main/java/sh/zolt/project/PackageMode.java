package sh.zolt.project;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

public enum PackageMode {
    THIN("thin"),
    SPRING_BOOT("spring-boot"),
    WAR("war"),
    SPRING_BOOT_WAR("spring-boot-war"),
    QUARKUS("quarkus"),
    UBER("uber"),
    BOM("bom");

    private final String configValue;

    PackageMode(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    /** The Maven artifact type emitted for this package mode. */
    public String artifactType() {
        return switch (this) {
            case WAR, SPRING_BOOT_WAR -> "war";
            case BOM -> "pom";
            case THIN, SPRING_BOOT, QUARKUS, UBER -> "jar";
        };
    }

    public static Optional<PackageMode> fromConfigValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (PackageMode mode : values()) {
            if (mode.configValue.equals(value)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    public static String supportedValues() {
        return Arrays.stream(values())
                .map(PackageMode::configValue)
                .collect(Collectors.joining(", "));
    }
}
