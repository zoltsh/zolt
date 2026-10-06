package sh.zolt.project;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

public enum GeneratedSourceKind {
    DECLARED_ROOT("declared-root", true),
    OPENAPI("openapi", true),
    PROTOBUF("protobuf", true),
    EXEC("exec", true),
    KSP("ksp", false);

    private final String configValue;
    private final boolean publiclySupported;

    GeneratedSourceKind(String configValue, boolean publiclySupported) {
        this.configValue = configValue;
        this.publiclySupported = publiclySupported;
    }

    public String configValue() {
        return configValue;
    }

    public static Optional<GeneratedSourceKind> fromConfigValue(String value) {
        return Arrays.stream(values())
                .filter(kind -> kind.publiclySupported)
                .filter(kind -> kind.configValue.equals(value))
                .findFirst();
    }

    public static String supportedValues() {
        return Arrays.stream(values())
                .filter(kind -> kind.publiclySupported)
                .map(GeneratedSourceKind::configValue)
                .collect(Collectors.joining(", "));
    }
}
