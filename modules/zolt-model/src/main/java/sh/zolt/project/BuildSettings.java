package sh.zolt.project;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record BuildSettings(
        String source,
        List<String> sourceRoots,
        String test,
        String outputRoot,
        String output,
        String testOutput,
        TestSourceRoots testSourceRoots,
        String integrationTestOutput,
        List<String> integrationTestSources,
        List<String> integrationTestResourceRoots,
        List<String> resourceRoots,
        List<String> testResourceRoots,
        ResourceFilteringSettings resourceFiltering,
        TestRuntimeSettings testRuntime,
        Map<String, TestSuiteSettings> testSuites,
        BuildMetadataSettings metadata,
        List<GeneratedSourceStep> generatedMainSources,
        List<GeneratedSourceStep> generatedTestSources) implements TestSourceRootSettings {
    private static final String DEFAULT_SOURCE_ROOT = "src/main/java";
    private static final String DEFAULT_TEST_ROOT = "src/test/java";
    private static final String DEFAULT_OUTPUT_ROOT = "target";
    private static final List<String> DEFAULT_RESOURCE_ROOTS = List.of("src/main/resources");
    private static final List<String> DEFAULT_TEST_RESOURCE_ROOTS = List.of("src/test/resources");
    private static final List<String> DEFAULT_INTEGRATION_TEST_SOURCES = List.of("src/integration-test/java");
    private static final List<String> DEFAULT_INTEGRATION_TEST_RESOURCES = List.of("src/integration-test/resources");

    public BuildSettings {
        sourceRoots = sourceRootsOrDefault(source, sourceRoots);
        source = sourceRoots.getFirst();
        outputRoot = outputRoot == null ? DEFAULT_OUTPUT_ROOT : outputRoot;
        testSourceRoots = testSourceRoots == null ? TestSourceRoots.defaults(test) : testSourceRoots;
        integrationTestOutput = integrationTestOutput == null ? outputRoot + "/integration-test-classes" : integrationTestOutput;
        integrationTestSources = copyOrDefault(integrationTestSources, DEFAULT_INTEGRATION_TEST_SOURCES);
        integrationTestResourceRoots = copyOrDefault(integrationTestResourceRoots, DEFAULT_INTEGRATION_TEST_RESOURCES);
        resourceRoots = copyOrDefault(resourceRoots, DEFAULT_RESOURCE_ROOTS);
        testResourceRoots = copyOrDefault(testResourceRoots, DEFAULT_TEST_RESOURCE_ROOTS);
        resourceFiltering = resourceFiltering == null ? ResourceFilteringSettings.defaults() : resourceFiltering;
        testRuntime = testRuntime == null ? TestRuntimeSettings.defaults() : testRuntime;
        testSuites = orderedTestSuites(testSuites);
        metadata = metadata == null ? BuildMetadataSettings.defaults() : metadata;
        generatedMainSources = copyOrDefault(generatedMainSources, List.of());
        generatedTestSources = copyOrDefault(generatedTestSources, List.of());
    }

    public BuildSettings(
            String source,
            String test,
            String outputRoot,
            String output,
            String testOutput,
            List<String> testSources,
            List<String> groovyTestSources,
            List<String> resourceRoots,
            List<String> testResourceRoots,
            BuildMetadataSettings metadata) {
        this(source, null, test, outputRoot, output, testOutput,
                TestSourceRoots.from(test, testSources, groovyTestSources, null), null, null, null,
                resourceRoots, testResourceRoots, ResourceFilteringSettings.defaults(), TestRuntimeSettings.defaults(),
                Map.of(),
                metadata, List.of(), List.of());
    }

    public BuildSettings(
            String source,
            List<String> sourceRoots,
            String test,
            String outputRoot,
            String output,
            String testOutput,
            List<String> testSources,
            List<String> groovyTestSources,
            List<String> kotlinTestSources,
            List<String> resourceRoots,
            List<String> testResourceRoots,
            BuildMetadataSettings metadata) {
        this(source, sourceRoots, test, outputRoot, output, testOutput,
                TestSourceRoots.from(test, testSources, groovyTestSources, kotlinTestSources), null, null, null,
                resourceRoots, testResourceRoots,
                ResourceFilteringSettings.defaults(), TestRuntimeSettings.defaults(), Map.of(), metadata,
                List.of(), List.of());
    }

    public BuildSettings(
            String source,
            List<String> sourceRoots,
            String test,
            String outputRoot,
            String output,
            String testOutput,
            List<String> testSources,
            List<String> groovyTestSources,
            List<String> resourceRoots,
            List<String> testResourceRoots,
            BuildMetadataSettings metadata) {
        this(source, sourceRoots, test, outputRoot, output, testOutput,
                TestSourceRoots.from(test, testSources, groovyTestSources, null), null, null, null,
                resourceRoots, testResourceRoots, ResourceFilteringSettings.defaults(), TestRuntimeSettings.defaults(),
                Map.of(),
                metadata, List.of(), List.of());
    }

    public BuildSettings(
            String source,
            String test,
            String output,
            String testOutput,
            List<String> testSources,
            List<String> groovyTestSources,
            String integrationTestOutput,
            List<String> integrationTestSources,
            List<String> integrationTestResourceRoots,
            List<String> resourceRoots,
            List<String> testResourceRoots,
            ResourceFilteringSettings resourceFiltering,
            TestRuntimeSettings testRuntime,
            BuildMetadataSettings metadata,
            List<GeneratedSourceStep> generatedMainSources,
            List<GeneratedSourceStep> generatedTestSources) {
        this(source, null, test, DEFAULT_OUTPUT_ROOT, output, testOutput,
                TestSourceRoots.from(test, testSources, groovyTestSources, null),
                integrationTestOutput, integrationTestSources, integrationTestResourceRoots, resourceRoots,
                testResourceRoots, resourceFiltering, testRuntime, Map.of(), metadata, generatedMainSources,
                generatedTestSources);
    }

    public BuildSettings(String source, String test, String outputRoot, String output, String testOutput) {
        this(source, null, test, outputRoot, output, testOutput, TestSourceRoots.defaults(test), null, null, null,
                DEFAULT_RESOURCE_ROOTS, DEFAULT_TEST_RESOURCE_ROOTS, ResourceFilteringSettings.defaults(),
                TestRuntimeSettings.defaults(), Map.of(), BuildMetadataSettings.defaults(), List.of(), List.of());
    }

    public BuildSettings(
            String source,
            String test,
            String output,
            String testOutput,
            List<String> testSources,
            List<String> groovyTestSources,
            List<String> resourceRoots,
            List<String> testResourceRoots,
            BuildMetadataSettings metadata) {
        this(source, null, test, DEFAULT_OUTPUT_ROOT, output, testOutput,
                TestSourceRoots.from(test, testSources, groovyTestSources, null), null, null, null,
                resourceRoots, testResourceRoots, ResourceFilteringSettings.defaults(),
                TestRuntimeSettings.defaults(), Map.of(), metadata, List.of(), List.of());
    }

    public BuildSettings(
            String source,
            String test,
            String output,
            String testOutput,
            List<String> testSources,
            List<String> groovyTestSources,
            List<String> resourceRoots,
            List<String> testResourceRoots,
            ResourceFilteringSettings resourceFiltering,
            BuildMetadataSettings metadata) {
        this(source, null, test, DEFAULT_OUTPUT_ROOT, output, testOutput,
                TestSourceRoots.from(test, testSources, groovyTestSources, null), null, null, null,
                resourceRoots, testResourceRoots, resourceFiltering, TestRuntimeSettings.defaults(), Map.of(),
                metadata, List.of(), List.of());
    }

    public BuildSettings(
            String source,
            String test,
            String output,
            String testOutput,
            List<String> testSources,
            List<String> resourceRoots,
            List<String> testResourceRoots,
            BuildMetadataSettings metadata) {
        this(source, null, test, DEFAULT_OUTPUT_ROOT, output, testOutput,
                TestSourceRoots.from(test, testSources, null, null), null, null, null,
                resourceRoots, testResourceRoots, ResourceFilteringSettings.defaults(), TestRuntimeSettings.defaults(),
                Map.of(), metadata, List.of(), List.of());
    }

    public BuildSettings(
            String source,
            String test,
            String output,
            String testOutput,
            List<String> testSources,
            List<String> groovyTestSources) {
        this(source, null, test, DEFAULT_OUTPUT_ROOT, output, testOutput,
                TestSourceRoots.from(test, testSources, groovyTestSources, null), null, null, null,
                DEFAULT_RESOURCE_ROOTS, DEFAULT_TEST_RESOURCE_ROOTS, ResourceFilteringSettings.defaults(),
                TestRuntimeSettings.defaults(), Map.of(), BuildMetadataSettings.defaults(), List.of(), List.of());
    }

    public BuildSettings(
            String source,
            String test,
            String output,
            String testOutput,
            List<String> testSources,
            BuildMetadataSettings metadata) {
        this(source, null, test, DEFAULT_OUTPUT_ROOT, output, testOutput,
                TestSourceRoots.from(test, testSources, null, null), null, null, null,
                DEFAULT_RESOURCE_ROOTS, DEFAULT_TEST_RESOURCE_ROOTS, ResourceFilteringSettings.defaults(),
                TestRuntimeSettings.defaults(), Map.of(), metadata, List.of(), List.of());
    }

    public BuildSettings(String source, String test, String output, String testOutput, List<String> testSources) {
        this(source, null, test, DEFAULT_OUTPUT_ROOT, output, testOutput,
                TestSourceRoots.from(test, testSources, null, null), null, null, null,
                DEFAULT_RESOURCE_ROOTS, DEFAULT_TEST_RESOURCE_ROOTS, ResourceFilteringSettings.defaults(),
                TestRuntimeSettings.defaults(), Map.of(), BuildMetadataSettings.defaults(), List.of(), List.of());
    }

    public BuildSettings(String source, String test, String output, String testOutput) {
        this(source, null, test, DEFAULT_OUTPUT_ROOT, output, testOutput, TestSourceRoots.defaults(test), null, null, null,
                DEFAULT_RESOURCE_ROOTS, DEFAULT_TEST_RESOURCE_ROOTS, ResourceFilteringSettings.defaults(),
                TestRuntimeSettings.defaults(), Map.of(), BuildMetadataSettings.defaults(), List.of(), List.of());
    }

    public static BuildSettings defaults() {
        return new BuildSettings(
                DEFAULT_SOURCE_ROOT,
                DEFAULT_TEST_ROOT,
                DEFAULT_OUTPUT_ROOT,
                DEFAULT_OUTPUT_ROOT + "/classes",
                DEFAULT_OUTPUT_ROOT + "/test-classes");
    }

    public BuildSettings withGeneratedSources(
            List<GeneratedSourceStep> generatedMainSources,
            List<GeneratedSourceStep> generatedTestSources) {
        return new BuildSettings(source, sourceRoots, test, outputRoot, output, testOutput, testSourceRoots,
                integrationTestOutput, integrationTestSources, integrationTestResourceRoots, resourceRoots,
                testResourceRoots, resourceFiltering, testRuntime, testSuites, metadata, generatedMainSources,
                generatedTestSources);
    }

    public BuildSettings withResourceFiltering(ResourceFilteringSettings resourceFiltering) {
        return new BuildSettings(source, sourceRoots, test, outputRoot, output, testOutput, testSourceRoots,
                integrationTestOutput, integrationTestSources, integrationTestResourceRoots, resourceRoots,
                testResourceRoots, resourceFiltering, testRuntime, testSuites, metadata, generatedMainSources,
                generatedTestSources);
    }

    public BuildSettings withTestRuntime(TestRuntimeSettings testRuntime) {
        return new BuildSettings(source, sourceRoots, test, outputRoot, output, testOutput, testSourceRoots,
                integrationTestOutput, integrationTestSources, integrationTestResourceRoots, resourceRoots,
                testResourceRoots, resourceFiltering, testRuntime, testSuites, metadata, generatedMainSources,
                generatedTestSources);
    }

    public BuildSettings withTestSuites(Map<String, TestSuiteSettings> testSuites) {
        return new BuildSettings(source, sourceRoots, test, outputRoot, output, testOutput, testSourceRoots,
                integrationTestOutput, integrationTestSources, integrationTestResourceRoots, resourceRoots,
                testResourceRoots, resourceFiltering, testRuntime, testSuites, metadata, generatedMainSources,
                generatedTestSources);
    }

    public BuildSettings withIntegrationTestSettings(
            String integrationTestOutput,
            List<String> integrationTestSources,
            List<String> integrationTestResourceRoots) {
        return new BuildSettings(source, sourceRoots, test, outputRoot, output, testOutput, testSourceRoots,
                integrationTestOutput, integrationTestSources, integrationTestResourceRoots, resourceRoots,
                testResourceRoots, resourceFiltering, testRuntime, testSuites, metadata, generatedMainSources,
                generatedTestSources);
    }

    public BuildSettings asIntegrationTestBuild() {
        return new BuildSettings(source, sourceRoots,
                integrationTestSources.isEmpty() ? "src/integration-test/java" : integrationTestSources.getFirst(),
                outputRoot, output, integrationTestOutput,
                new TestSourceRoots(integrationTestSources, List.of(), List.of()), integrationTestOutput,
                integrationTestSources, integrationTestResourceRoots, resourceRoots, integrationTestResourceRoots,
                resourceFiltering, testRuntime, testSuites, metadata, generatedMainSources, generatedTestSources);
    }

    private static List<String> sourceRootsOrDefault(String source, List<String> roots) {
        if (roots == null || roots.isEmpty()) {
            String primary = source == null || source.isBlank() ? DEFAULT_SOURCE_ROOT : source;
            return List.of(primary);
        }
        return List.copyOf(new java.util.LinkedHashSet<>(roots));
    }

    private static <T> List<T> copyOrDefault(List<T> values, List<T> defaults) {
        return values == null ? defaults : List.copyOf(values);
    }

    private static Map<String, TestSuiteSettings> orderedTestSuites(Map<String, TestSuiteSettings> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, TestSuiteSettings> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, TestSuiteSettings> entry : new TreeMap<>(values).entrySet()) {
            ordered.put(entry.getKey(), entry.getValue() == null ? TestSuiteSettings.empty() : entry.getValue());
        }
        return Collections.unmodifiableMap(ordered);
    }
}
