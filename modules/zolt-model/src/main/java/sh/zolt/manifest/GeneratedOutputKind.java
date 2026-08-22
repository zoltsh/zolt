package sh.zolt.manifest;

/** The build lane that consumes one exec generated-step output. */
public enum GeneratedOutputKind {
    JAVA_SOURCES("java-sources"),
    TEST_SOURCES("test-sources"),
    RESOURCES("resources"),
    TEST_RESOURCES("test-resources"),
    INTERMEDIATE("intermediate");

    private final String configValue;

    GeneratedOutputKind(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public boolean producesResources() {
        return this == RESOURCES || this == TEST_RESOURCES;
    }
}
