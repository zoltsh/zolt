package sh.zolt.manifest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
        options = immutableStringMap(options, "OpenAPI option");
        additionalProperties = immutableStringMap(
                additionalProperties, "OpenAPI additional-property");
        configOptions = immutableStringMap(configOptions, "OpenAPI config-option");
        globalProperties = immutableStringMap(
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
