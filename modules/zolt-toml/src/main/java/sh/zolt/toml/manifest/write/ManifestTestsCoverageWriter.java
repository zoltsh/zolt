package sh.zolt.toml.manifest.write;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.CoveragePercentage;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.TestClassPattern;
import sh.zolt.manifest.authored.AuthoredCoverage;
import sh.zolt.manifest.authored.AuthoredTestRuntime;
import sh.zolt.manifest.authored.AuthoredTestSuite;
import sh.zolt.manifest.authored.AuthoredTests;
import sh.zolt.toml.schema.FinalManifestCoverageFields;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.FinalManifestTestFields;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSection;

/** Emits canonical authored test and coverage settings without materializing defaults. */
final class ManifestTestsCoverageWriter {
    private static final String CONVENTIONAL_TEST_JAVA = "src/test/java";
    private static final String CONVENTIONAL_INTEGRATION_SOURCE =
            "src/integration-test/java";
    private static final String CONVENTIONAL_INTEGRATION_RESOURCE =
            "src/integration-test/resources";
    private static final int DEFAULT_SUITE_WORKERS = 1;

    private static final ManifestSection TEST_SOURCES =
            section(FinalManifestPaths.TEST_SOURCES);
    private static final ManifestSection TEST_RUNTIME =
            section(FinalManifestPaths.TEST_RUNTIME);
    private static final ManifestSection TEST_INTEGRATION =
            section(FinalManifestPaths.TEST_INTEGRATION);
    private static final ManifestSection TEST_SUITE =
            section(FinalManifestPaths.TEST_SUITE);
    private static final ManifestSection COVERAGE =
            section(FinalManifestPaths.COVERAGE);

    void write(
            ManifestTomlEmitter emitter,
            Optional<AuthoredTests> tests,
            Optional<AuthoredCoverage> coverage) {
        ManifestTomlEmitter output =
                Objects.requireNonNull(emitter, "Manifest TOML emitter is required.");
        Objects.requireNonNull(tests, "Authored tests are required.")
                .ifPresent(value -> writeTests(output, value));
        Objects.requireNonNull(coverage, "Authored coverage is required.")
                .ifPresent(value -> writeCoverage(output, value));
    }

    private static void writeTests(
            ManifestTomlEmitter emitter, AuthoredTests tests) {
        tests.sources().ifPresent(value -> writeSources(emitter, value));
        tests.runtime().ifPresent(value -> writeRuntime(emitter, value));
        tests.integration().ifPresent(value -> writeIntegration(emitter, value));
        tests.suites().entrySet().stream()
                .sorted(Comparator.comparing(
                        entry -> entry.getKey().value(),
                        ManifestModelValues.CODE_POINT_ORDER))
                .forEach(entry -> writeSuite(emitter, entry.getKey(), entry.getValue()));
    }

    private static void writeSources(
            ManifestTomlEmitter emitter, AuthoredTests.Sources sources) {
        emitter.section(TEST_SOURCES);
        if (!sources.java().isEmpty()
                && !isConventional(sources.java(), CONVENTIONAL_TEST_JAVA)) {
            emitter.field(
                    FinalManifestTestFields.TEST_SOURCES_JAVA,
                    paths(sources.java()));
        }
        if (!sources.groovy().isEmpty()) {
            emitter.field(
                    FinalManifestTestFields.TEST_SOURCES_GROOVY,
                    paths(sources.groovy()));
        }
    }

    private static void writeRuntime(
            ManifestTomlEmitter emitter, AuthoredTestRuntime runtime) {
        emitter.section(TEST_RUNTIME);
        if (!runtime.jvmArgs().isEmpty()) {
            emitter.field(
                    FinalManifestTestFields.TEST_RUNTIME_JVM_ARGS,
                    strings(runtime.jvmArgs()));
        }
        if (!runtime.properties().isEmpty()) {
            emitter.field(
                    FinalManifestTestFields.TEST_RUNTIME_PROPERTIES,
                    properties(runtime.properties()));
        }
        if (!runtime.env().isEmpty()) {
            emitter.field(
                    FinalManifestTestFields.TEST_RUNTIME_ENV,
                    environment(runtime.env()));
        }
        if (!runtime.events().isEmpty()) {
            emitter.field(
                    FinalManifestTestFields.TEST_RUNTIME_EVENTS,
                    strings(runtime.events().stream()
                            .map(AuthoredTestRuntime.Event::configValue)
                            .toList()));
        }
    }

    private static void writeIntegration(
            ManifestTomlEmitter emitter, AuthoredTests.Integration integration) {
        emitter.section(TEST_INTEGRATION);
        if (!integration.sources().isEmpty()
                && !isConventional(
                        integration.sources(), CONVENTIONAL_INTEGRATION_SOURCE)) {
            emitter.field(
                    FinalManifestTestFields.TEST_INTEGRATION_SOURCES,
                    paths(integration.sources()));
        }
        if (!integration.resources().isEmpty()
                && !isConventional(
                        integration.resources(), CONVENTIONAL_INTEGRATION_RESOURCE)) {
            emitter.field(
                    FinalManifestTestFields.TEST_INTEGRATION_RESOURCES,
                    paths(integration.resources()));
        }
    }

    private static void writeSuite(
            ManifestTomlEmitter emitter, LocalId id, AuthoredTestSuite suite) {
        emitter.namedSection(TEST_SUITE, id.value());
        writePatterns(
                emitter, FinalManifestTestFields.TEST_SUITE_CLASSES, suite.classes());
        writePatterns(
                emitter,
                FinalManifestTestFields.TEST_SUITE_EXCLUDE_CLASSES,
                suite.excludeClasses());
        writeStrings(emitter, FinalManifestTestFields.TEST_SUITE_TAGS, suite.tags());
        writeStrings(
                emitter,
                FinalManifestTestFields.TEST_SUITE_EXCLUDE_TAGS,
                suite.excludeTags());

        boolean hasOtherContent = !suite.classes().isEmpty()
                || !suite.excludeClasses().isEmpty()
                || !suite.tags().isEmpty()
                || !suite.excludeTags().isEmpty()
                || !suite.locks().isEmpty();
        suite.workers()
                .filter(value -> value != DEFAULT_SUITE_WORKERS || !hasOtherContent)
                .ifPresent(value -> emitter.field(
                        FinalManifestTestFields.TEST_SUITE_WORKERS,
                        ManifestTomlValueEncoder.integer(value)));
        if (!suite.locks().isEmpty()) {
            emitter.field(
                    FinalManifestTestFields.TEST_SUITE_LOCKS,
                    locks(suite.locks()));
        }
    }

    private static void writeCoverage(
            ManifestTomlEmitter emitter, AuthoredCoverage coverage) {
        emitter.section(COVERAGE);
        coverage.line().ifPresent(value -> writeFloor(
                emitter, FinalManifestCoverageFields.COVERAGE_LINE, value));
        coverage.branch().ifPresent(value -> writeFloor(
                emitter, FinalManifestCoverageFields.COVERAGE_BRANCH, value));
        coverage.instruction().ifPresent(value -> writeFloor(
                emitter, FinalManifestCoverageFields.COVERAGE_INSTRUCTION, value));
        coverage.method().ifPresent(value -> writeFloor(
                emitter, FinalManifestCoverageFields.COVERAGE_METHOD, value));
    }

    private static void writeFloor(
            ManifestTomlEmitter emitter,
            ManifestField field,
            CoveragePercentage floor) {
        emitter.field(field, ManifestTomlValueEncoder.decimal(floor.value()));
    }

    private static void writePatterns(
            ManifestTomlEmitter emitter,
            ManifestField field,
            List<TestClassPattern> values) {
        if (!values.isEmpty()) {
            emitter.field(
                    field,
                    strings(values.stream().map(TestClassPattern::value).toList()));
        }
    }

    private static void writeStrings(
            ManifestTomlEmitter emitter,
            ManifestField field,
            List<String> values) {
        if (!values.isEmpty()) {
            emitter.field(field, strings(values));
        }
    }

    private static String properties(Map<String, String> values) {
        return ManifestTomlValueEncoder.inlineObject(values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(ManifestModelValues.CODE_POINT_ORDER))
                .map(entry -> ManifestTomlValueEncoder.quotedMember(
                        entry.getKey(), string(entry.getValue())))
                .toList());
    }

    private static String environment(
            Map<EnvironmentVariableName, String> values) {
        return ManifestTomlValueEncoder.inlineObject(values.entrySet().stream()
                .sorted(Comparator.comparing(
                        entry -> entry.getKey().value(),
                        ManifestModelValues.CODE_POINT_ORDER))
                .map(entry -> ManifestTomlValueEncoder.quotedMember(
                        entry.getKey().value(), string(entry.getValue())))
                .toList());
    }

    private static String locks(List<AuthoredTestSuite.Lock> values) {
        return ManifestTomlValueEncoder.array(values.stream()
                .sorted(Comparator.comparing(
                        lock -> lock.className().value(),
                        ManifestModelValues.CODE_POINT_ORDER))
                .map(ManifestTestsCoverageWriter::lock)
                .toList());
    }

    private static String lock(AuthoredTestSuite.Lock lock) {
        List<String> resources = lock.resources().stream()
                .map(LocalId::value)
                .sorted(ManifestModelValues.CODE_POINT_ORDER)
                .toList();
        return ManifestTomlValueEncoder.inlineObject(List.of(
                ManifestTomlValueEncoder.member(
                        FinalManifestObjectShapes.TEST_SUITE_LOCK_CLASS.name(),
                        string(lock.className().value())),
                ManifestTomlValueEncoder.member(
                        FinalManifestObjectShapes.TEST_SUITE_LOCK_RESOURCES.name(),
                        strings(resources))));
    }

    private static boolean isConventional(
            List<ManifestRelativePath> paths, String conventional) {
        return paths.size() == 1 && conventional.equals(paths.getFirst().value());
    }

    private static String paths(List<ManifestRelativePath> paths) {
        return strings(paths.stream().map(ManifestRelativePath::value).toList());
    }

    private static String strings(List<String> values) {
        return ManifestTomlValueEncoder.array(
                values.stream().map(ManifestTestsCoverageWriter::string).toList());
    }

    private static String string(String value) {
        return ManifestTomlValueEncoder.basicString(value);
    }

    private static ManifestSection section(ManifestPath path) {
        return FinalManifestSchema.registry().section(path).orElseThrow();
    }
}
