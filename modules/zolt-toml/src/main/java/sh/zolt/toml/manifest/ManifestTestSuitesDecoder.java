package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.manifest.JavaBinaryClassName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.TestClassPattern;
import sh.zolt.manifest.authored.AuthoredTestSuite;
import sh.zolt.toml.schema.FinalManifestObjectShapes;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestTestFields;

/** Collects authored named test suites while preserving omission and source-order validation. */
final class ManifestTestSuitesDecoder {
    private final ManifestTestSuiteDecoder suiteDecoder = new ManifestTestSuiteDecoder();

    Optional<Map<LocalId, AuthoredTestSuite>> decode(ManifestDecodeIndex index) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        List<ManifestDecodeIndex.SectionEntry> entries =
                index.sectionEntries(FinalManifestPaths.TEST_SUITE);
        if (index.section(FinalManifestPaths.TEST_SUITES).isEmpty() && entries.isEmpty()) {
            return Optional.empty();
        }

        LinkedHashMap<LocalId, AuthoredTestSuite> suites = new LinkedHashMap<>();
        for (ManifestDecodeIndex.SectionEntry entry : entries) {
            ManifestTestSuiteDecoder.Decoded decoded = suiteDecoder.decode(index, entry);
            if (suites.put(decoded.id(), decoded.suite()) != null) {
                throw new IllegalStateException(
                        "Validated manifest contains duplicate test suite `" + decoded.id() + "`.");
            }
        }
        return Optional.of(ManifestModelValues.immutableSortedMap(
                suites,
                LocalId::compareTo,
                "Test suite ID",
                "Authored test suite"));
    }
}

/** Decodes one authored named test suite without applying defaults or scheduling policy. */
final class ManifestTestSuiteDecoder {
    Decoded decode(
            ManifestDecodeIndex index,
            ManifestDecodeIndex.SectionEntry entry) {
        Objects.requireNonNull(index, "Manifest decode index is required.");
        Objects.requireNonNull(entry, "Manifest test suite section entry is required.");

        LocalId id = ManifestSemanticDiagnostics.construct(
                entry.section(), () -> new LocalId(entry.key()));
        Optional<ValidatedManifestField> classesField =
                index.field(entry, FinalManifestTestFields.TEST_SUITE_CLASSES);
        Optional<ValidatedManifestField> excludeClassesField =
                index.field(entry, FinalManifestTestFields.TEST_SUITE_EXCLUDE_CLASSES);
        Optional<ValidatedManifestField> tagsField =
                index.field(entry, FinalManifestTestFields.TEST_SUITE_TAGS);
        Optional<ValidatedManifestField> excludeTagsField =
                index.field(entry, FinalManifestTestFields.TEST_SUITE_EXCLUDE_TAGS);
        Optional<ValidatedManifestField> workersField =
                index.field(entry, FinalManifestTestFields.TEST_SUITE_WORKERS);
        Optional<ValidatedManifestField> locksField =
                index.field(entry, FinalManifestTestFields.TEST_SUITE_LOCKS);

        List<TestClassPattern> classes = classesField
                .map(field -> patterns(field, false))
                .orElse(List.of());
        List<TestClassPattern> excludeClasses = excludeClassesField
                .map(field -> patterns(field, true))
                .orElse(List.of());
        List<String> tags = tagsField
                .map(field -> tags(field, false))
                .orElse(List.of());
        List<String> excludeTags = excludeTagsField
                .map(field -> tags(field, true))
                .orElse(List.of());
        Optional<Integer> workers = workersField.map(ManifestTestSuiteDecoder::workers);
        List<AuthoredTestSuite.Lock> locks = locksField
                .map(ManifestTestSuiteDecoder::locks)
                .orElse(List.of());

        ValidatedManifestField anchor = first(
                classesField,
                excludeClassesField,
                tagsField,
                excludeTagsField,
                workersField,
                locksField);
        AuthoredTestSuite suite = ManifestSemanticDiagnostics.construct(
                anchor,
                () -> new AuthoredTestSuite(
                        classes, excludeClasses, tags, excludeTags, workers, locks));
        return new Decoded(id, suite);
    }

    private static List<TestClassPattern> patterns(
            ValidatedManifestField field,
            boolean excluded) {
        List<String> raw = ManifestTomlValues.strings(field);
        ArrayList<TestClassPattern> values = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            int item = index;
            TestClassPattern pattern = ManifestSemanticDiagnostics.construct(
                    field, item, () -> new TestClassPattern(raw.get(item)));
            values.add(pattern);
            List<TestClassPattern> prefix = List.copyOf(values);
            ManifestSemanticDiagnostics.construct(
                    field,
                    item,
                    () -> selectionSuite(
                            excluded ? List.of() : prefix,
                            excluded ? prefix : List.of(),
                            List.of(),
                            List.of()));
        }
        return List.copyOf(values);
    }

    private static List<String> tags(
            ValidatedManifestField field,
            boolean excluded) {
        List<String> raw = ManifestTomlValues.strings(field);
        ArrayList<String> values = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            values.add(raw.get(index));
            List<String> prefix = List.copyOf(values);
            ManifestSemanticDiagnostics.construct(
                    field,
                    index,
                    () -> selectionSuite(
                            List.of(),
                            List.of(),
                            excluded ? List.of() : prefix,
                            excluded ? prefix : List.of()));
        }
        return List.copyOf(values);
    }

    private static int workers(ValidatedManifestField field) {
        long raw = ManifestTomlValues.integer(field);
        int value = ManifestSemanticDiagnostics.construct(field, () -> checkedWorker(raw));
        ManifestSemanticDiagnostics.construct(
                field,
                () -> new AuthoredTestSuite(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        Optional.of(value),
                        List.of()));
        return value;
    }

    private static int checkedWorker(long value) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Test suite workers must fit a signed 32-bit integer.");
        }
        return (int) value;
    }

    private static List<AuthoredTestSuite.Lock> locks(ValidatedManifestField field) {
        List<ManifestInlineTable> tables = ManifestTomlValues.inlineObjectArray(field);
        ArrayList<AuthoredTestSuite.Lock> values = new ArrayList<>(tables.size());
        for (ManifestInlineTable table : tables) {
            values.add(lock(table));
            List<AuthoredTestSuite.Lock> prefix = List.copyOf(values);
            ManifestSemanticDiagnostics.construct(
                    table,
                    FinalManifestObjectShapes.TEST_SUITE_LOCK_CLASS,
                    () -> new AuthoredTestSuite(
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            Optional.empty(),
                            prefix));
        }
        return List.copyOf(values);
    }

    private static AuthoredTestSuite.Lock lock(ManifestInlineTable table) {
        String rawClass = table.requiredString(
                FinalManifestObjectShapes.TEST_SUITE_LOCK_CLASS);
        JavaBinaryClassName className = ManifestSemanticDiagnostics.construct(
                table,
                FinalManifestObjectShapes.TEST_SUITE_LOCK_CLASS,
                () -> new JavaBinaryClassName(rawClass));
        List<String> rawResources = table.optionalStrings(
                        FinalManifestObjectShapes.TEST_SUITE_LOCK_RESOURCES)
                .orElseThrow();
        if (rawResources.isEmpty()) {
            return ManifestSemanticDiagnostics.construct(
                    table,
                    FinalManifestObjectShapes.TEST_SUITE_LOCK_RESOURCES,
                    () -> new AuthoredTestSuite.Lock(className, List.of()));
        }

        ArrayList<LocalId> resources = new ArrayList<>(rawResources.size());
        AuthoredTestSuite.Lock lock = null;
        for (int index = 0; index < rawResources.size(); index++) {
            int item = index;
            LocalId resource = ManifestSemanticDiagnostics.construct(
                    table,
                    FinalManifestObjectShapes.TEST_SUITE_LOCK_RESOURCES,
                    item,
                    () -> new LocalId(rawResources.get(item)));
            resources.add(resource);
            List<LocalId> prefix = List.copyOf(resources);
            lock = ManifestSemanticDiagnostics.construct(
                    table,
                    FinalManifestObjectShapes.TEST_SUITE_LOCK_RESOURCES,
                    item,
                    () -> new AuthoredTestSuite.Lock(className, prefix));
        }
        return Objects.requireNonNull(lock, "Decoded test suite lock is required.");
    }

    private static AuthoredTestSuite selectionSuite(
            List<TestClassPattern> classes,
            List<TestClassPattern> excludeClasses,
            List<String> tags,
            List<String> excludeTags) {
        return new AuthoredTestSuite(
                classes,
                excludeClasses,
                tags,
                excludeTags,
                Optional.empty(),
                List.of());
    }

    @SafeVarargs
    private static ValidatedManifestField first(
            Optional<ValidatedManifestField>... fields) {
        for (Optional<ValidatedManifestField> field : fields) {
            if (field.isPresent()) {
                return field.orElseThrow();
            }
        }
        throw new IllegalStateException("Authored test suite has no source field.");
    }

    record Decoded(LocalId id, AuthoredTestSuite suite) {
        Decoded {
            Objects.requireNonNull(id, "Authored test suite ID is required.");
            Objects.requireNonNull(suite, "Authored test suite is required.");
        }
    }
}
