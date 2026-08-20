package sh.zolt.toml.manifest;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.ResourceGlob;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.toml.schema.ManifestDynamicKeyGrammar;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestSchemaMatch;
import sh.zolt.toml.schema.ManifestSymbolRegistry;
import sh.zolt.toml.schema.ManifestValidationCategory;
import sh.zolt.toml.schema.MutationPolicy;

/** Value, symbol, dynamic-key, path, and environment checks for one matched field. */
final class ManifestShapeFieldValidator {
    private final ManifestSymbolRegistry symbols;
    private final ManifestSchemaNavigator navigator;
    private final ManifestShapeDiagnostics diagnostics;
    private final ManifestShapeObjectValidator objectValidator;

    ManifestShapeFieldValidator(
            ManifestSymbolRegistry symbols,
            ManifestSchemaNavigator navigator,
            ManifestShapeDiagnostics diagnostics) {
        this.symbols = symbols;
        this.navigator = navigator;
        this.diagnostics = diagnostics;
        this.objectValidator = new ManifestShapeObjectValidator(diagnostics);
    }

    boolean validate(
            ManifestSchemaMatch<ManifestField> match,
            Object value,
            ManifestShapeSource source) {
        ManifestField field = match.descriptor();
        String path = concretePath(field.path().segments(), match.bindings());
        boolean keysValid = validateDynamicKeys(
                field.path().segments(),
                match.bindings(),
                field.dynamicKeyGrammars(),
                navigator.reservedBinding(field.path(), match.bindings(), java.util.Set.of()),
                source,
                path);
        if (!ManifestShapeValueKinds.matches(field.valueKind(), value)) {
            diagnostics.add(source, "Invalid value for `" + path + "`: expected "
                    + ManifestShapeValueKinds.expected(field.valueKind()) + " but found "
                    + ManifestShapeValueKinds.actual(value) + ".");
            return false;
        }
        boolean selectorValid = validateMutableSelector(field, value, source, path);
        validateSymbols(field, value, source, path);
        validateDirect(field.validation(), value, source, path);
        boolean objectValid = field.objectShape()
                .filter(ignored -> value instanceof TomlTable)
                .map(shape -> objectValidator.validate(shape, (TomlTable) value, source, path))
                .orElse(true);
        return keysValid && selectorValid && objectValid;
    }

    boolean validateDynamicKeys(
            java.util.List<String> pattern,
            Map<String, String> bindings,
            Map<String, ManifestDynamicKeyGrammar> grammars,
            Optional<String> reserved,
            ManifestShapeSource source,
            String concretePath) {
        if (reserved.isPresent()) {
            diagnostics.add(source, "Manifest ID `" + reserved.orElseThrow()
                    + "` is reserved at `" + concretePath + "`.");
            return false;
        }
        boolean valid = true;
        for (String segment : pattern) {
            if (!ManifestSchemaNavigator.isPlaceholder(segment)) {
                continue;
            }
            String name = ManifestSchemaNavigator.placeholderName(segment);
            String value = bindings.get(name);
            try {
                validateDynamicKey(grammars.get(name), value);
            } catch (IllegalArgumentException exception) {
                diagnostics.add(source, "Invalid dynamic key `" + value + "` at `"
                        + concretePath + "`: " + exception.getMessage());
                valid = false;
            }
        }
        return valid;
    }

    private void validateSymbols(
            ManifestField field,
            Object value,
            ManifestShapeSource source,
            String path) {
        if (field.symbolFamily().isEmpty()) {
            return;
        }
        var family = symbols.family(field.symbolFamily().orElseThrow()).orElseThrow();
        Consumer<String> validate = item -> {
            if (!family.accepts(item)) {
                diagnostics.add(source, "Invalid symbol `" + item + "` for `" + path
                        + "`; expected one of " + family.values() + ".");
            }
        };
        if (value instanceof String string) {
            validate.accept(string);
        } else if (value instanceof TomlArray array) {
            for (int index = 0; index < array.size(); index++) {
                validate.accept((String) array.get(index));
            }
        }
    }

    private void validateDirect(
            ManifestValidationCategory category,
            Object value,
            ManifestShapeSource source,
            String path) {
        switch (category) {
            case NONE -> {
            }
            case LOCAL_ID -> validateStrings(value, item -> new LocalId(item), source, path);
            case MANIFEST_RELATIVE_PATH ->
                validateStrings(value, item -> new ManifestRelativePath(item), source, path);
            case WORKSPACE_MEMBER_PATH ->
                validateStrings(value, item -> new WorkspaceMemberPath(item), source, path);
            case WORKSPACE_MEMBER_PATTERN ->
                validateStrings(value, item -> new WorkspaceMemberPattern(item), source, path);
            case RESOURCE_GLOB ->
                validateStrings(value, item -> new ResourceGlob(item), source, path);
            case ENVIRONMENT_NAME ->
                validateEnvironmentStrings(value, source, path);
            case ENVIRONMENT_MAP_KEYS ->
                validateEnvironmentMap((TomlTable) value, false, source, path);
            case ENVIRONMENT_MAP_KEYS_AND_VALUES ->
                validateEnvironmentMap((TomlTable) value, true, source, path);
        }
    }

    private void validateStrings(
            Object value,
            Consumer<String> validator,
            ManifestShapeSource source,
            String path) {
        if (value instanceof String string) {
            validateString(string, validator, source, path);
        } else if (value instanceof TomlArray array) {
            for (int index = 0; index < array.size(); index++) {
                validateString((String) array.get(index), validator, source, path);
            }
        }
    }

    private void validateEnvironmentStrings(
            Object value,
            ManifestShapeSource source,
            String path) {
        HashMap<String, String> spellings = new HashMap<>();
        validateStrings(value, item -> {
            new EnvironmentVariableName(item);
            rejectCaseCollision(item, spellings);
        }, source, path);
    }

    private void validateEnvironmentMap(
            TomlTable table,
            boolean validateValues,
            ManifestShapeSource source,
            String path) {
        HashMap<String, String> keySpellings = new HashMap<>();
        HashMap<String, String> valueSpellings = new HashMap<>();
        table.entrySet().forEach(entry -> {
            validateString(entry.getKey(), item -> {
                new EnvironmentVariableName(item);
                rejectCaseCollision(item, keySpellings);
            }, source, path);
            if (!(entry.getValue() instanceof String string)) {
                diagnostics.add(source, "Invalid environment value in `" + path
                    + "`: expected a string but found "
                    + ManifestShapeValueKinds.actual(entry.getValue()) + ".");
                return;
            }
            if (!validateValues) {
                return;
            }
            validateString(string, item -> {
                new EnvironmentVariableName(item);
                rejectCaseCollision(item, valueSpellings);
            }, source, path);
        });
    }

    private void validateString(
            String value,
            Consumer<String> validator,
            ManifestShapeSource source,
            String path) {
        try {
            validator.accept(value);
        } catch (IllegalArgumentException exception) {
            diagnostics.add(source, "Invalid value for `" + path + "`: " + exception.getMessage());
        }
    }

    private static void rejectCaseCollision(String value, Map<String, String> spellings) {
        String folded = asciiLowercase(value);
        String existing = spellings.putIfAbsent(folded, value);
        if (existing != null && !existing.equals(value)) {
            throw new IllegalArgumentException("Environment-variable names `" + existing + "` and `"
                    + value + "` differ only by ASCII case.");
        }
    }

    private static void validateDynamicKey(ManifestDynamicKeyGrammar grammar, String value) {
        switch (grammar) {
            case LOCAL_ID -> new LocalId(value);
            case MAVEN_COORDINATE -> new DependencyCoordinate(value);
            case EXTERNAL_JAR_ATTRIBUTE -> {
                if (value == null
                        || value.isBlank()
                        || !value.equals(value.strip())
                        || value.codePoints().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException(
                            "JAR manifest attribute names must be nonblank, have no surrounding whitespace, "
                                    + "and contain no control characters.");
                }
            }
        }
    }

    private boolean validateMutableSelector(
            ManifestField field, Object value, ManifestShapeSource source, String path) {
        if (field.mutation() != MutationPolicy.REPLACE_ENTRY
                || !(value instanceof TomlTable table)
                || !table.isEmpty()) {
            return true;
        }
        diagnostics.add(source, "Mutable manifest entry `" + path
                + "` must not use an empty inline table `{}`; author its selector fields.");
        return false;
    }

    private static String concretePath(java.util.List<String> pattern, Map<String, String> bindings) {
        return pattern.stream()
                .map(segment -> ManifestSchemaNavigator.isPlaceholder(segment)
                        ? bindings.get(ManifestSchemaNavigator.placeholderName(segment))
                        : segment)
                .collect(java.util.stream.Collectors.joining("."));
    }

    private static String asciiLowercase(String value) {
        StringBuilder result = new StringBuilder(value.length());
        value.chars().forEach(character -> result.append(character >= 'A' && character <= 'Z'
                ? (char) (character + ('a' - 'A'))
                : (char) character));
        return result.toString();
    }
}
