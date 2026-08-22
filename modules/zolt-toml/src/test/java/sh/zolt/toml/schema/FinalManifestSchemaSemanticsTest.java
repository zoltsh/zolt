package sh.zolt.toml.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class FinalManifestSchemaSemanticsTest {
    private final ManifestSchemaRegistry registry = FinalManifestSchema.registry();

    @Test
    void associatesEveryDirectSymbolFieldWithItsClosedFamily() {
        assertEquals(
                Map.ofEntries(
                        symbol("toolchain.java.distribution", "toolchain-distribution"),
                        symbol("toolchain.java.features", "toolchain-feature"),
                        symbol("toolchain.java.policy", "toolchain-policy"),
                        symbol("toolchain.java.test.distribution", "toolchain-distribution"),
                        symbol("toolchain.java.test.policy", "toolchain-policy"),
                        symbol("dependencies.policy.conflicts", "conflict-policy"),
                        symbol("dependencies.policy.licenses.unknown", "unknown-license-policy"),
                        symbol("compiler.jdkApi", "compiler-jdk-api-mode"),
                        symbol("compiler.test.jdkApi", "compiler-jdk-api-mode"),
                        symbol("resources.filter.targets", "resource-filter-target"),
                        symbol("resources.filter.missing", "resource-missing-policy"),
                        symbol("generated.tools.<id>.kind", "generated-tool-kind"),
                        symbol("generated.presets.<id>.kind", "generated-preset-kind"),
                        symbol("generated.main.<id>.kind", "generated-step-kind"),
                        symbol("generated.main.<id>.language", "generated-language"),
                        symbol("generated.main.<id>.produces", "generated-lane"),
                        symbol("generated.main.<id>.cache", "generated-cache-policy"),
                        symbol("generated.test.<id>.kind", "generated-step-kind"),
                        symbol("generated.test.<id>.language", "generated-language"),
                        symbol("generated.test.<id>.produces", "generated-lane"),
                        symbol("generated.test.<id>.cache", "generated-cache-policy"),
                        symbol("test.runtime.events", "test-runtime-event-outcome"),
                        symbol("package.mode", "package-mode"),
                        symbol("package.duplicates", "package-duplicate-policy"),
                        symbol("publish.signing.method", "signing-method"),
                        symbol("publish.central.mode", "central-mode")),
                registry.fields().stream()
                        .filter(field -> field.symbolFamily().isPresent())
                        .collect(Collectors.toMap(
                                field -> field.path().toString(),
                                field -> field.symbolFamily().orElseThrow())));
    }

    @Test
    void routesEveryDirectPathAndEnvironmentFieldByTypedCategory() {
        EnumMap<ManifestValidationCategory, List<String>> actual = registry.fields().stream()
                .filter(field -> field.validation() != ManifestValidationCategory.NONE)
                .collect(Collectors.groupingBy(
                        ManifestField::validation,
                        () -> new EnumMap<>(ManifestValidationCategory.class),
                        Collectors.mapping(field -> field.path().toString(), Collectors.toList())));

        assertEquals(Map.of(
                ManifestValidationCategory.LOCAL_ID,
                List.of("workspace.name"),
                ManifestValidationCategory.WORKSPACE_MEMBER_PATH,
                List.of("workspace.members.default", "bom.members", "bom.exclude"),
                ManifestValidationCategory.WORKSPACE_MEMBER_PATTERN,
                List.of("workspace.members.include", "workspace.members.exclude"),
                ManifestValidationCategory.MANIFEST_RELATIVE_PATH,
                List.of(
                        "build.sources",
                        "build.output.root",
                        "build.output.main",
                        "build.output.test",
                        "build.output.integration",
                        "compiler.generated.main",
                        "compiler.generated.test",
                        "resources.main",
                        "resources.test",
                        "generated.presets.<id>.config",
                        "generated.presets.<id>.templateDir",
                        "generated.main.<id>.output",
                        "generated.main.<id>.into",
                        "generated.main.<id>.config",
                        "generated.main.<id>.templateDir",
                        "generated.main.<id>.cwd",
                        "generated.test.<id>.output",
                        "generated.test.<id>.into",
                        "generated.test.<id>.config",
                        "generated.test.<id>.templateDir",
                        "generated.test.<id>.cwd",
                        "test.sources.java",
                        "test.sources.groovy",
                        "test.integration.sources",
                        "test.integration.resources",
                        "native.output",
                        "tasks.<id>.cwd"),
                ManifestValidationCategory.RESOURCE_GLOB,
                List.of(
                        "resources.filter.include",
                        "generated.main.<id>.input",
                        "generated.main.<id>.inputs",
                        "generated.test.<id>.input",
                        "generated.test.<id>.inputs"),
                ManifestValidationCategory.ENVIRONMENT_NAME,
                List.of(
                        "credentials.<id>.tokenEnv",
                        "credentials.<id>.usernameEnv",
                        "credentials.<id>.passwordEnv",
                        "generated.main.<id>.inheritEnv",
                        "generated.test.<id>.inheritEnv",
                        "publish.signing.passphraseEnv",
                        "publish.central.tokenEnv"),
                ManifestValidationCategory.ENVIRONMENT_MAP_KEYS,
                List.of(
                        "generated.main.<id>.env",
                        "generated.test.<id>.env",
                        "test.runtime.env",
                        "tasks.<id>.env"),
                ManifestValidationCategory.ENVIRONMENT_MAP_KEYS_AND_VALUES,
                List.of("generated.main.<id>.secretEnv", "generated.test.<id>.secretEnv")), actual);
    }

    @Test
    void assignsGrammarToEveryPlaceholderOnFieldsAndNamedSections() {
        Stream.concat(registry.fields().stream(), registry.sections().stream())
                .forEach(descriptor -> assertDynamicKeys(
                        descriptor instanceof ManifestField field ? field.path() : ((ManifestSection) descriptor).path(),
                        descriptor instanceof ManifestField field
                                ? field.dynamicKeyGrammars()
                                : ((ManifestSection) descriptor).dynamicKeyGrammars()));

        assertEquals(
                Map.ofEntries(
                        Map.entry("project.developers.<id>", localId()),
                        Map.entry("repositories.<id>", localId()),
                        Map.entry("credentials.<id>", localId()),
                        Map.entry("dependencies.license-exceptions.<coordinate>", coordinate()),
                        Map.entry("generated.tools.<id>", localId()),
                        Map.entry("generated.presets.<id>", localId()),
                        Map.entry("generated.main.<id>", localId()),
                        Map.entry("generated.test.<id>", localId()),
                        Map.entry("test.suites.<id>", localId()),
                        Map.entry("publish.repositories.<id>", localId()),
                        Map.entry("tasks.<id>", localId())),
                registry.sections().stream()
                        .filter(section -> !section.dynamicKeyGrammars().isEmpty())
                        .collect(Collectors.toMap(
                                section -> section.path().toString(),
                                ManifestSection::dynamicKeyGrammars)));
        assertEquals(localId(), field("versions.<id>").dynamicKeyGrammars());
        assertEquals(coordinate(), field("dependencies.<coordinate>").dynamicKeyGrammars());
        assertEquals(coordinate(), field("bom.imports.<coordinate>").dynamicKeyGrammars());
        assertEquals(
                Map.of("attribute", ManifestDynamicKeyGrammar.EXTERNAL_JAR_ATTRIBUTE),
                field("package.manifest.<attribute>").dynamicKeyGrammars());
        assertEquals(localId(), field("aliases.<id>").dynamicKeyGrammars());
    }

    @Test
    void leavesInlineUnionSemanticsToDomainConstruction() {
        for (String path : List.of(
                "repositories.central",
                "resources.tokens.<id>")) {
            ManifestField field = field(path);
            assertTrue(field.symbolFamily().isEmpty());
            assertEquals(ManifestValidationCategory.NONE, field.validation());
        }
    }

    private void assertDynamicKeys(
            ManifestPath path,
            Map<String, ManifestDynamicKeyGrammar> actual) {
        LinkedHashMap<String, ManifestDynamicKeyGrammar> expected = new LinkedHashMap<>();
        path.placeholderNames().forEach(placeholder -> expected.put(placeholder, switch (placeholder) {
            case "id" -> ManifestDynamicKeyGrammar.LOCAL_ID;
            case "coordinate" -> ManifestDynamicKeyGrammar.MAVEN_COORDINATE;
            case "attribute" -> ManifestDynamicKeyGrammar.EXTERNAL_JAR_ATTRIBUTE;
            default -> throw new AssertionError("Unexpected placeholder: " + placeholder);
        }));
        assertEquals(expected, actual, path.toString());
    }

    private ManifestField field(String path) {
        return registry.field(ManifestPath.of(path.split("\\.")[0], tail(path))).orElseThrow();
    }

    private static String[] tail(String path) {
        String[] segments = path.split("\\.");
        return java.util.Arrays.copyOfRange(segments, 1, segments.length);
    }

    private static Map.Entry<String, String> symbol(String path, String family) {
        return Map.entry(path, family);
    }

    private static Map<String, ManifestDynamicKeyGrammar> localId() {
        return Map.of("id", ManifestDynamicKeyGrammar.LOCAL_ID);
    }

    private static Map<String, ManifestDynamicKeyGrammar> coordinate() {
        return Map.of("coordinate", ManifestDynamicKeyGrammar.MAVEN_COORDINATE);
    }
}
