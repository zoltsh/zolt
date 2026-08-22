package sh.zolt.manifest.authored;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.ManifestRelativePath;

/** Authored OpenAPI preset or step-local options before preset composition. */
public record AuthoredOpenApiOptions(
        Optional<String> generator,
        Optional<String> library,
        Optional<String> apiPackage,
        Optional<String> modelPackage,
        Optional<String> invokerPackage,
        Optional<ManifestRelativePath> config,
        Optional<ManifestRelativePath> templateDir,
        Optional<Boolean> validateSpec,
        Map<String, String> options,
        Map<String, String> additionalProperties,
        Map<String, String> configOptions,
        Map<String, String> globalProperties,
        Map<String, String> typeMappings,
        Map<String, String> importMappings) {
    /**
     * Generator options that make the OpenAPI generator shell out to an external command once per
     * generated file. Zolt never runs generator post-processing hooks: the hook would execute outside
     * process supervision and outside generated-source fingerprinting, so it is rejected at the model
     * boundary — for presets and for step-local overrides alike, whatever authored source produced them.
     */
    private static final Set<String> POST_PROCESS_KEYS = Set.of(
            "enablepostprocessfile",
            "postprocessfile",
            "apifilepostprocessfile",
            "modelfilepostprocessfile");

    public AuthoredOpenApiOptions {
        generator = optionalText(generator, "OpenAPI generator");
        library = optionalText(library, "OpenAPI library");
        apiPackage = optionalText(apiPackage, "OpenAPI API package");
        modelPackage = optionalText(modelPackage, "OpenAPI model package");
        invokerPackage = optionalText(invokerPackage, "OpenAPI invoker package");
        config = Objects.requireNonNull(config, "OpenAPI config path must not be null.");
        templateDir = Objects.requireNonNull(
                templateDir, "OpenAPI template-directory path must not be null.");
        validateSpec = Objects.requireNonNull(
                validateSpec, "OpenAPI validate-spec setting must not be null.");
        options = generatorOptionMap(options, "OpenAPI option");
        additionalProperties = generatorOptionMap(
                additionalProperties, "OpenAPI additional-property");
        configOptions = generatorOptionMap(configOptions, "OpenAPI config-option");
        globalProperties = generatorOptionMap(
                globalProperties, "OpenAPI global-property");
        typeMappings = immutableStringMap(typeMappings, "OpenAPI type-mapping");
        importMappings = immutableStringMap(importMappings, "OpenAPI import-mapping");
    }

    public static AuthoredOpenApiOptions empty() {
        return new AuthoredOpenApiOptions(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static Optional<String> optionalText(Optional<String> value, String label) {
        Objects.requireNonNull(value, label + " must not be null.");
        value.ifPresent(text -> {
            ManifestModelValues.requireNonBlank(text, label);
            ManifestModelValues.rejectControlCharacters(text, label);
        });
        return value;
    }

    private static Map<String, String> generatorOptionMap(
            Map<String, String> values, String label) {
        Map<String, String> copy = immutableStringMap(values, label);
        for (String key : copy.keySet()) {
            String normalized = key.toLowerCase(Locale.ROOT);
            if (POST_PROCESS_KEYS.contains(normalized) || normalized.contains("postprocess")) {
                throw new IllegalArgumentException(
                        "Unsupported " + label + " `" + key
                                + "`. Zolt does not run generator post-processing hooks; remove the"
                                + " option or model the behavior as a Zolt-owned generated-source"
                                + " feature.");
            }
        }
        return copy;
    }

    private static Map<String, String> immutableStringMap(
            Map<String, String> values, String label) {
        Objects.requireNonNull(values, label + " map must not be null.");
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(ManifestModelValues.CODE_POINT_ORDER))
                .forEach(entry -> {
                    String key = Objects.requireNonNull(
                            entry.getKey(), label + " key must not be null.");
                    String value = Objects.requireNonNull(
                            entry.getValue(), label + " value must not be null.");
                    ManifestModelValues.requireNonBlank(key, label + " key");
                    ManifestModelValues.rejectControlCharacters(key, label + " key");
                    if (value.indexOf('\0') >= 0) {
                        throw new IllegalArgumentException(
                                label + " value must not contain NUL.");
                    }
                    copy.put(key, value);
                });
        return Collections.unmodifiableMap(copy);
    }
}
