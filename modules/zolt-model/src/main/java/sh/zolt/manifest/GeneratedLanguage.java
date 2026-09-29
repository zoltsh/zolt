package sh.zolt.manifest;

/** Source languages supported by final generated-step declarations. */
public enum GeneratedLanguage {
    JAVA("java"),
    KOTLIN("kotlin");

    private final String configValue;

    GeneratedLanguage(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public GeneratedLanguage requireJavaFor(String stepKind) {
        if (this != JAVA) {
            throw new IllegalArgumentException(
                    stepKind + " generated steps support only language `java`.");
        }
        return this;
    }
}
