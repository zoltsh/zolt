package sh.zolt.project;

import java.util.List;

public record CompilerSettings(
        String generatedSources,
        String generatedTestSources,
        String release,
        String encoding,
        List<String> args,
        List<String> testArgs,
        String platformApi,
        String testPlatformApi,
        String groovyVersion) {
    private static final String DEFAULT_GENERATED_SOURCES = "target/generated/sources/annotations";
    private static final String DEFAULT_GENERATED_TEST_SOURCES = "target/generated/test-sources/annotations";

    /** The default, reproducible platform-API mode: {@code javac --release N} pinned to ct.sym. */
    public static final String PLATFORM_API_RELEASE = "release";

    /** The opt-in host-JDK platform-API mode: {@code javac -source N -target N}, not reproducible. */
    public static final String PLATFORM_API_HOST = "host";

    public CompilerSettings {
        generatedSources = stringOrDefault(generatedSources, DEFAULT_GENERATED_SOURCES);
        generatedTestSources = stringOrDefault(generatedTestSources, DEFAULT_GENERATED_TEST_SOURCES);
        release = stringOrEmpty(release);
        encoding = stringOrEmpty(encoding);
        args = copyArgs(args, "args");
        testArgs = copyArgs(testArgs, "testArgs");
        platformApi = platformApiOrDefault(platformApi);
        testPlatformApi = stringOrEmpty(testPlatformApi);
        groovyVersion = stringOrEmpty(groovyVersion);
    }

    /** Compatibility constructor for callers that predate explicit Groovy compiler selection. */
    public CompilerSettings(
            String generatedSources,
            String generatedTestSources,
            String release,
            String encoding,
            List<String> args,
            List<String> testArgs,
            String platformApi,
            String testPlatformApi) {
        this(
                generatedSources,
                generatedTestSources,
                release,
                encoding,
                args,
                testArgs,
                platformApi,
                testPlatformApi,
                "");
    }

    public CompilerSettings(
            String generatedSources,
            String generatedTestSources,
            String release,
            String encoding,
            List<String> args,
            List<String> testArgs) {
        this(generatedSources, generatedTestSources, release, encoding, args, testArgs, PLATFORM_API_RELEASE, "");
    }

    public CompilerSettings(String generatedSources, String generatedTestSources) {
        this(generatedSources, generatedTestSources, "", "", List.of(), List.of());
    }

    public static CompilerSettings defaults() {
        return new CompilerSettings(
                DEFAULT_GENERATED_SOURCES,
                DEFAULT_GENERATED_TEST_SOURCES);
    }

    public static CompilerSettings defaultsForOutputRoot(String outputRoot) {
        String root = outputRoot == null || outputRoot.isBlank() ? "target" : outputRoot;
        return new CompilerSettings(
                root + "/generated/sources/annotations",
                root + "/generated/test-sources/annotations");
    }

    /**
     * The platform-API mode governing test compilation: {@link #testPlatformApi()} when it is set,
     * otherwise it inherits {@link #platformApi()}. Mirrors how {@code testArgs} layer on {@code args}.
     */
    public String effectiveTestPlatformApi() {
        return testPlatformApi.isBlank() ? platformApi : testPlatformApi;
    }

    /** Whether main compilation should use the host JDK platform API instead of {@code --release}. */
    public boolean mainHostPlatformApi() {
        return PLATFORM_API_HOST.equals(platformApi);
    }

    /** Whether test compilation should use the host JDK platform API instead of {@code --release}. */
    public boolean testHostPlatformApi() {
        return PLATFORM_API_HOST.equals(effectiveTestPlatformApi());
    }

    private static String stringOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String stringOrEmpty(String value) {
        return value == null || value.isBlank() ? "" : value;
    }

    private static String platformApiOrDefault(String value) {
        return value == null || value.isBlank() ? PLATFORM_API_RELEASE : value;
    }

    private static List<String> copyArgs(List<String> values, String name) {
        if (values == null) {
            return List.of();
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Compiler " + name + " must contain non-empty strings.");
            }
        }
        return List.copyOf(values);
    }
}
