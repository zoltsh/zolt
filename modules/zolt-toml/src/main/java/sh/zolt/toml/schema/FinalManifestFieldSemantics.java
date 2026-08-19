package sh.zolt.toml.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Registry-owned semantic routes layered on the final field and placeholder catalog. */
final class FinalManifestFieldSemantics {
    private static final Map<String, String> SYMBOL_FAMILIES = Map.ofEntries(
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
            symbol("publish.central.mode", "central-mode"));

    private static final Map<String, ManifestValidationCategory> VALIDATION = Map.ofEntries(
            validation("workspace.members.default", ManifestValidationCategory.WORKSPACE_MEMBER_PATH),
            validation("workspace.members.include", ManifestValidationCategory.WORKSPACE_MEMBER_PATTERN),
            validation("workspace.members.exclude", ManifestValidationCategory.WORKSPACE_MEMBER_PATTERN),
            validation("credentials.<id>.tokenEnv", ManifestValidationCategory.ENVIRONMENT_NAME),
            validation("credentials.<id>.usernameEnv", ManifestValidationCategory.ENVIRONMENT_NAME),
            validation("credentials.<id>.passwordEnv", ManifestValidationCategory.ENVIRONMENT_NAME),
            validation("build.sources", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("build.output.root", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("build.output.main", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("build.output.test", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("build.output.integration", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("compiler.generated.main", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("compiler.generated.test", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("resources.main", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("resources.test", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("resources.filter.include", ManifestValidationCategory.RESOURCE_GLOB),
            validation("generated.presets.<id>.config", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("generated.presets.<id>.templateDir", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("generated.main.<id>.input", ManifestValidationCategory.RESOURCE_GLOB),
            validation("generated.main.<id>.inputs", ManifestValidationCategory.RESOURCE_GLOB),
            validation("generated.main.<id>.output", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("generated.main.<id>.into", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("generated.main.<id>.config", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("generated.main.<id>.templateDir", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("generated.main.<id>.cwd", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("generated.main.<id>.env", ManifestValidationCategory.ENVIRONMENT_MAP_KEYS),
            validation(
                    "generated.main.<id>.secretEnv",
                    ManifestValidationCategory.ENVIRONMENT_MAP_KEYS_AND_VALUES),
            validation("generated.main.<id>.inheritEnv", ManifestValidationCategory.ENVIRONMENT_NAME),
            validation("generated.test.<id>.input", ManifestValidationCategory.RESOURCE_GLOB),
            validation("generated.test.<id>.inputs", ManifestValidationCategory.RESOURCE_GLOB),
            validation("generated.test.<id>.output", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("generated.test.<id>.into", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("generated.test.<id>.config", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("generated.test.<id>.templateDir", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("generated.test.<id>.cwd", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("generated.test.<id>.env", ManifestValidationCategory.ENVIRONMENT_MAP_KEYS),
            validation(
                    "generated.test.<id>.secretEnv",
                    ManifestValidationCategory.ENVIRONMENT_MAP_KEYS_AND_VALUES),
            validation("generated.test.<id>.inheritEnv", ManifestValidationCategory.ENVIRONMENT_NAME),
            validation("test.sources.java", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("test.sources.groovy", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("test.runtime.env", ManifestValidationCategory.ENVIRONMENT_MAP_KEYS),
            validation("test.integration.sources", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("test.integration.resources", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("bom.members", ManifestValidationCategory.WORKSPACE_MEMBER_PATH),
            validation("bom.exclude", ManifestValidationCategory.WORKSPACE_MEMBER_PATH),
            validation("native.output", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("publish.signing.passphraseEnv", ManifestValidationCategory.ENVIRONMENT_NAME),
            validation("publish.central.tokenEnv", ManifestValidationCategory.ENVIRONMENT_NAME),
            validation("tasks.<id>.cwd", ManifestValidationCategory.MANIFEST_RELATIVE_PATH),
            validation("tasks.<id>.env", ManifestValidationCategory.ENVIRONMENT_MAP_KEYS));

    private FinalManifestFieldSemantics() {
    }

    static Metadata field(ManifestPath path) {
        String canonicalPath = path.toString();
        return new Metadata(
                Optional.ofNullable(SYMBOL_FAMILIES.get(canonicalPath)),
                VALIDATION.getOrDefault(canonicalPath, ManifestValidationCategory.NONE));
    }

    static void validateCatalog(List<ManifestField> fields) {
        Set<String> paths = fields.stream()
                .map(field -> field.path().toString())
                .collect(Collectors.toUnmodifiableSet());
        validatePaths("symbol-family", SYMBOL_FAMILIES.keySet(), paths);
        validatePaths("validation", VALIDATION.keySet(), paths);
    }

    static Map<String, ManifestDynamicKeyGrammar> dynamicKeys(ManifestPath path) {
        LinkedHashMap<String, ManifestDynamicKeyGrammar> grammars = new LinkedHashMap<>();
        for (String placeholder : path.placeholderNames()) {
            grammars.put(placeholder, switch (placeholder) {
                case "id" -> ManifestDynamicKeyGrammar.LOCAL_ID;
                case "coordinate" -> ManifestDynamicKeyGrammar.MAVEN_COORDINATE;
                case "attribute" -> ManifestDynamicKeyGrammar.EXTERNAL_JAR_ATTRIBUTE;
                default -> throw new IllegalArgumentException(
                        "No final manifest grammar is registered for placeholder `<"
                                + placeholder + ">`.");
            });
        }
        return Collections.unmodifiableMap(grammars);
    }

    private static Map.Entry<String, String> symbol(String path, String family) {
        return Map.entry(path, family);
    }

    private static Map.Entry<String, ManifestValidationCategory> validation(
            String path,
            ManifestValidationCategory category) {
        return Map.entry(path, category);
    }

    private static void validatePaths(String kind, Set<String> configured, Set<String> catalog) {
        for (String path : configured) {
            if (!catalog.contains(path)) {
                throw new IllegalStateException(
                        "Final manifest " + kind + " metadata references unknown field `" + path + "`.");
            }
        }
    }

    record Metadata(
            Optional<String> symbolFamily,
            ManifestValidationCategory validation) {
    }
}
