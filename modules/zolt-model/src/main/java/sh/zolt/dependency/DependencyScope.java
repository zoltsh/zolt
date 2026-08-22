package sh.zolt.dependency;

public enum DependencyScope {
    COMPILE(true, true, true, false, false, true, "compile"),
    RUNTIME(false, true, true, false, false, true, "runtime"),
    DEV(false, true, false, false, false, false, "dev"),
    TEST(false, false, true, false, false, false, "test"),
    PROVIDED(true, false, true, false, false, false, "provided"),
    PROCESSOR(false, false, false, true, false, false, "processor"),
    TEST_PROCESSOR(false, false, false, false, true, false, "test-processor"),
    QUARKUS_DEPLOYMENT(false, false, false, false, false, false, "quarkus-deployment"),
    TOOL_SPRING_AOT(false, false, false, false, false, false, "tool-spring-aot"),
    TOOL_OPENAPI(false, false, false, false, false, false, "tool-openapi"),
    TOOL_PROTOBUF(false, false, false, false, false, false, "tool-protobuf"),
    TOOL_EXEC(false, false, false, false, false, false, "tool-exec"),
    TOOL_COVERAGE(false, false, false, false, false, false, "tool-coverage");

    private final boolean mainCompileClasspath;
    private final boolean mainRuntimeClasspath;
    private final boolean testClasspaths;
    private final boolean mainProcessorClasspath;
    private final boolean testProcessorClasspath;
    private final boolean packagedByDefault;
    private final String lockfileName;

    DependencyScope(
            boolean mainCompileClasspath,
            boolean mainRuntimeClasspath,
            boolean testClasspaths,
            boolean mainProcessorClasspath,
            boolean testProcessorClasspath,
            boolean packagedByDefault,
            String lockfileName) {
        this.mainCompileClasspath = mainCompileClasspath;
        this.mainRuntimeClasspath = mainRuntimeClasspath;
        this.testClasspaths = testClasspaths;
        this.mainProcessorClasspath = mainProcessorClasspath;
        this.testProcessorClasspath = testProcessorClasspath;
        this.packagedByDefault = packagedByDefault;
        this.lockfileName = lockfileName;
    }

    public boolean entersMainCompileClasspath() {
        return mainCompileClasspath;
    }

    public boolean entersMainRuntimeClasspath() {
        return mainRuntimeClasspath;
    }

    /**
     * Whether a package resolved in this scope is visible to test compilation.
     *
     * <p>Per design §9.2 the test lanes see the project's api, implementation, runtime, and provided
     * lanes plus test dependencies. Development-only, annotation-processor, and tooling scopes never
     * reach test code.
     */
    public boolean entersTestCompileClasspath() {
        return testClasspaths;
    }

    /**
     * Whether a package resolved in this scope is present when tests execute.
     *
     * <p>Membership is identical to {@link #entersTestCompileClasspath()}; the two lanes are distinct
     * concepts and each caller names the one it means.
     */
    public boolean entersTestRuntimeClasspath() {
        return testClasspaths;
    }

    public boolean entersMainProcessorClasspath() {
        return mainProcessorClasspath;
    }

    public boolean entersTestProcessorClasspath() {
        return testProcessorClasspath;
    }

    public boolean packagedByDefault() {
        return packagedByDefault;
    }

    public String lockfileName() {
        return lockfileName;
    }
}
