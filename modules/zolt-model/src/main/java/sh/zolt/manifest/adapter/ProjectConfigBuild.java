package sh.zolt.manifest.adapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.TestClassPattern;
import sh.zolt.manifest.authored.AuthoredBuild;
import sh.zolt.manifest.authored.AuthoredCompiler;
import sh.zolt.manifest.authored.AuthoredResources;
import sh.zolt.manifest.authored.AuthoredTestRuntime;
import sh.zolt.manifest.authored.AuthoredTestSuite;
import sh.zolt.manifest.authored.AuthoredTests;
import sh.zolt.project.BuildMetadataSettings;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ResourceFilteringSettings;
import sh.zolt.project.ResourceMissingTokenPolicy;
import sh.zolt.project.ResourceTokenSettings;
import sh.zolt.project.TestRuntimeSettings;
import sh.zolt.project.TestSuiteSettings;

/**
 * Projects the final {@code [build]}, {@code [compiler]}, {@code [resources]}, and {@code [test]}
 * domains onto the legacy {@link BuildSettings} and {@link CompilerSettings} records.
 *
 * <p>Final output and generated-annotation paths are relative to {@code [build.output].root}
 * (design §10.2 and §10.4) while the legacy fields are project-relative, so the adapter joins them
 * with the effective output root.
 */
final class ProjectConfigBuild {
    static final String DEFAULT_SOURCE_ROOT = "src/main/java";
    static final String DEFAULT_TEST_ROOT = "src/test/java";
    static final String DEFAULT_OUTPUT_ROOT = "target";
    private static final String DEFAULT_MAIN_OUTPUT = "classes";
    private static final String DEFAULT_TEST_OUTPUT = "test-classes";
    private static final String DEFAULT_INTEGRATION_OUTPUT = "integration-test-classes";
    private static final String DEFAULT_GENERATED_MAIN = "generated/sources/annotations";
    private static final String DEFAULT_GENERATED_TEST = "generated/test-sources/annotations";
    private static final List<String> DEFAULT_MAIN_RESOURCES = List.of("src/main/resources");
    private static final List<String> DEFAULT_TEST_RESOURCES = List.of("src/test/resources");
    private static final List<String> DEFAULT_INTEGRATION_SOURCES = List.of("src/integration-test/java");
    private static final List<String> DEFAULT_INTEGRATION_RESOURCES = List.of("src/integration-test/resources");

    private ProjectConfigBuild() {
    }

    /** The effective {@code [build.output].root}, which every derived output path is relative to. */
    static String outputRoot(Optional<AuthoredBuild> build) {
        return build.flatMap(AuthoredBuild::output)
                .flatMap(AuthoredBuild.Output::root)
                .map(ManifestRelativePath::value)
                .orElse(DEFAULT_OUTPUT_ROOT);
    }

    static BuildSettings build(
            Optional<AuthoredBuild> build,
            Optional<AuthoredResources> resources,
            Optional<AuthoredTests> tests,
            List<GeneratedSourceStep> generatedMainSources,
            List<GeneratedSourceStep> generatedTestSources) {
        String outputRoot = outputRoot(build);
        Optional<AuthoredBuild.Output> output = build.flatMap(AuthoredBuild::output);
        List<String> sourceRoots = paths(build.map(AuthoredBuild::sources).orElse(List.of()));
        List<String> testSources = tests.flatMap(AuthoredTests::sources)
                .map(sources -> paths(sources.java()))
                .filter(values -> !values.isEmpty())
                .orElse(List.of(DEFAULT_TEST_ROOT));
        return new BuildSettings(
                sourceRoots.isEmpty() ? DEFAULT_SOURCE_ROOT : sourceRoots.getFirst(),
                sourceRoots.isEmpty() ? List.of(DEFAULT_SOURCE_ROOT) : sourceRoots,
                testSources.getFirst(),
                outputRoot,
                joined(outputRoot, output.flatMap(AuthoredBuild.Output::main), DEFAULT_MAIN_OUTPUT),
                joined(outputRoot, output.flatMap(AuthoredBuild.Output::test), DEFAULT_TEST_OUTPUT),
                testSources,
                tests.flatMap(AuthoredTests::sources)
                        .map(sources -> paths(sources.groovy()))
                        .orElse(List.of()),
                joined(outputRoot, output.flatMap(AuthoredBuild.Output::integration), DEFAULT_INTEGRATION_OUTPUT),
                integrationSources(tests),
                integrationResources(tests),
                resourceRoots(resources.map(AuthoredResources::main), DEFAULT_MAIN_RESOURCES),
                resourceRoots(resources.map(AuthoredResources::test), DEFAULT_TEST_RESOURCES),
                filtering(resources),
                testRuntime(tests),
                testSuites(tests),
                metadata(build),
                generatedMainSources,
                generatedTestSources);
    }

    static CompilerSettings compiler(Optional<AuthoredCompiler> compiler, String outputRoot) {
        Optional<AuthoredCompiler.Generated> generated = compiler.flatMap(AuthoredCompiler::generated);
        Optional<AuthoredCompiler.Test> test = compiler.flatMap(AuthoredCompiler::test);
        return new CompilerSettings(
                joined(outputRoot, generated.flatMap(AuthoredCompiler.Generated::main), DEFAULT_GENERATED_MAIN),
                joined(outputRoot, generated.flatMap(AuthoredCompiler.Generated::test), DEFAULT_GENERATED_TEST),
                "",
                compiler.flatMap(AuthoredCompiler::encoding).orElse(""),
                compiler.map(AuthoredCompiler::args).orElse(List.of()),
                test.map(AuthoredCompiler.Test::args).orElse(List.of()),
                compiler.flatMap(AuthoredCompiler::jdkApi)
                        .map(AuthoredCompiler.JdkApiMode::configValue)
                        .orElse(CompilerSettings.PLATFORM_API_RELEASE),
                test.flatMap(AuthoredCompiler.Test::jdkApi)
                        .map(AuthoredCompiler.JdkApiMode::configValue)
                        .orElse(""));
    }

    private static List<String> integrationSources(Optional<AuthoredTests> tests) {
        return tests.flatMap(AuthoredTests::integration)
                .map(integration -> paths(integration.sources()))
                .filter(values -> !values.isEmpty())
                .orElse(DEFAULT_INTEGRATION_SOURCES);
    }

    private static List<String> integrationResources(Optional<AuthoredTests> tests) {
        return tests.flatMap(AuthoredTests::integration)
                .map(integration -> paths(integration.resources()))
                .filter(values -> !values.isEmpty())
                .orElse(DEFAULT_INTEGRATION_RESOURCES);
    }

    private static List<String> resourceRoots(
            Optional<List<ManifestRelativePath>> roots,
            List<String> defaults) {
        return roots.map(ProjectConfigBuild::paths)
                .filter(values -> !values.isEmpty())
                .orElse(defaults);
    }

    private static ResourceFilteringSettings filtering(Optional<AuthoredResources> resources) {
        Optional<AuthoredResources.Filter> filter = resources.flatMap(AuthoredResources::filter);
        Map<String, ResourceTokenSettings> tokens = tokens(resources);
        if (filter.isEmpty()) {
            ResourceFilteringSettings defaults = ResourceFilteringSettings.defaults();
            return tokens.isEmpty()
                    ? defaults
                    : new ResourceFilteringSettings(
                            defaults.enabled(),
                            defaults.testEnabled(),
                            defaults.includes(),
                            defaults.missing(),
                            tokens);
        }
        AuthoredResources.Filter authored = filter.orElseThrow();
        List<AuthoredResources.Target> targets = authored.targets()
                .orElse(List.of(AuthoredResources.Target.MAIN));
        return new ResourceFilteringSettings(
                targets.contains(AuthoredResources.Target.MAIN),
                targets.contains(AuthoredResources.Target.TEST),
                authored.include().stream().map(glob -> glob.value()).toList(),
                authored.missing()
                        .map(missing -> missing == AuthoredResources.MissingTokenPolicy.KEEP
                                ? ResourceMissingTokenPolicy.KEEP
                                : ResourceMissingTokenPolicy.FAIL)
                        .orElse(ResourceMissingTokenPolicy.FAIL),
                tokens);
    }

    private static Map<String, ResourceTokenSettings> tokens(Optional<AuthoredResources> resources) {
        Map<String, ResourceTokenSettings> tokens = new LinkedHashMap<>();
        resources.map(AuthoredResources::tokens)
                .orElse(Map.of())
                .forEach((id, token) -> tokens.put(id.value(), token(token)));
        return Map.copyOf(tokens);
    }

    private static ResourceTokenSettings token(AuthoredResources.Token token) {
        return switch (token) {
            case AuthoredResources.Token.Literal literal ->
                    ResourceTokenSettings.literal(literal.value());
            case AuthoredResources.Token.Environment environment ->
                    ResourceTokenSettings.env(environment.env().value());
            case AuthoredResources.Token.Project project ->
                    ResourceTokenSettings.project(project.field().configValue());
        };
    }

    private static TestRuntimeSettings testRuntime(Optional<AuthoredTests> tests) {
        Optional<AuthoredTestRuntime> runtime = tests.flatMap(AuthoredTests::runtime);
        if (runtime.isEmpty()) {
            return TestRuntimeSettings.defaults();
        }
        AuthoredTestRuntime authored = runtime.orElseThrow();
        Map<String, String> environment = new LinkedHashMap<>();
        authored.env().forEach((name, value) -> environment.put(name.value(), value));
        return new TestRuntimeSettings(
                authored.jvmArgs(),
                authored.properties(),
                environment,
                authored.events().stream().map(AuthoredTestRuntime.Event::configValue).toList());
    }

    private static Map<String, TestSuiteSettings> testSuites(Optional<AuthoredTests> tests) {
        Map<String, TestSuiteSettings> suites = new LinkedHashMap<>();
        tests.map(AuthoredTests::suites)
                .orElse(Map.of())
                .forEach((id, suite) -> suites.put(id.value(), suite(suite)));
        return Map.copyOf(suites);
    }

    private static TestSuiteSettings suite(AuthoredTestSuite suite) {
        int workers = suite.workers().orElse(1);
        Map<String, List<String>> locks = new LinkedHashMap<>();
        for (AuthoredTestSuite.Lock lock : suite.locks()) {
            locks.put(
                    lock.className().value(),
                    lock.resources().stream().map(LocalId::value).toList());
        }
        return new TestSuiteSettings(
                patterns(suite.classes()),
                patterns(suite.excludeClasses()),
                suite.tags(),
                suite.excludeTags(),
                workers > 1,
                workers,
                locks);
    }

    private static List<String> patterns(List<TestClassPattern> patterns) {
        return patterns.stream().map(TestClassPattern::value).toList();
    }

    private static BuildMetadataSettings metadata(Optional<AuthoredBuild> build) {
        Optional<AuthoredBuild.Metadata> metadata = build.flatMap(AuthoredBuild::metadata);
        if (metadata.isEmpty()) {
            return BuildMetadataSettings.defaults();
        }
        AuthoredBuild.Metadata authored = metadata.orElseThrow();
        return new BuildMetadataSettings(
                authored.buildInfo().orElse(false),
                authored.git().orElse(false),
                authored.reproducible().orElse(false));
    }

    private static List<String> paths(List<ManifestRelativePath> paths) {
        List<String> values = new ArrayList<>(paths.size());
        paths.forEach(path -> values.add(path.value()));
        return List.copyOf(values);
    }

    static String joined(String outputRoot, Optional<ManifestRelativePath> path, String fallback) {
        return outputRoot + "/" + path.map(ManifestRelativePath::value).orElse(fallback);
    }
}
