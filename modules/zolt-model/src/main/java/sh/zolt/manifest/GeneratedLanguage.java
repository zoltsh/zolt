package sh.zolt.manifest;

/** Source languages supported by final generated-step declarations. */
public enum GeneratedLanguage {
    JAVA("java");

    private final String configValue;

    GeneratedLanguage(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }
}
