package sh.zolt.toml.manifest.write;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.FormattingPolicy;
import sh.zolt.toml.schema.ManifestDynamicKeyGrammar;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSchemaRegistry;
import sh.zolt.toml.schema.ManifestSection;
import sh.zolt.toml.schema.SectionKind;

final class ManifestTomlEmitter {
    private static final ManifestSchemaRegistry SCHEMA = FinalManifestSchema.registry();
    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_-]+");

    private final StringBuilder output = new StringBuilder();
    private final Set<ManifestPath> emittedSections = new HashSet<>();
    private SectionState current;
    private EmissionOrder lastSection;
    private boolean finished;

    void section(ManifestSection section) {
        requireRegistered(section);
        if (!section.path().placeholderNames().isEmpty()) {
            throw new IllegalArgumentException(
                    "Manifest section `" + section.path() + "` requires a dynamic name.");
        }
        openSection(section, section.path(), Map.of());
    }

    void namedSection(ManifestSection section, String name) {
        requireRegistered(section);
        List<String> placeholders = section.path().placeholderNames();
        if (section.kind() != SectionKind.NAMED_ITEM || placeholders.size() != 1) {
            throw new IllegalArgumentException(
                    "Manifest section `" + section.path() + "` is not a named-item section.");
        }
        String placeholder = placeholders.getFirst();
        validateDynamicKey(section.dynamicKeyGrammars().get(placeholder), name);
        if (section.reservedChildren().contains(name)) {
            throw new IllegalArgumentException(
                    "Manifest name `" + name + "` is reserved at `" + section.path() + "`.");
        }
        ManifestPath concrete = bind(section.path(), placeholder, name);
        if (SCHEMA.matchSection(concrete)
                        .filter(match -> match.descriptor() == section)
                        .isEmpty()) {
            throw new IllegalArgumentException(
                    "Manifest section handle `" + section.path()
                            + "` does not match concrete section `" + concrete + "`.");
        }
        openSection(section, concrete, Map.of(placeholder, name));
    }

    void field(ManifestField field, String renderedValue) {
        emitField(field, false, null, renderedValue);
    }

    void dynamicField(ManifestField field, String key, String renderedValue) {
        emitField(field, true, key, renderedValue);
    }

    String finish() {
        requireOpen();
        flushSection();
        finished = true;
        return output.toString();
    }

    private void openSection(ManifestSection section, ManifestPath concrete, Map<String, String> bindings) {
        requireOpen();
        flushSection();
        current = new SectionState(section, concrete, bindings);
    }

    private void emitField(ManifestField field, boolean dynamicInvocation, String dynamicKey, String renderedValue) {
        requireOpen();
        requireRegistered(field);
        if (current == null) {
            throw new IllegalStateException("A manifest section must be selected before emitting a field.");
        }

        List<String> pattern = field.path().segments();
        String finalSegment = pattern.getLast();
        boolean dynamic = isPlaceholder(finalSegment);
        if (dynamic != dynamicInvocation) {
            String expectation = dynamic ? "requires a dynamic key" : "has a fixed key";
            throw new IllegalArgumentException(
                    "Manifest field `" + field.path() + "` " + expectation + ".");
        }
        requireCurrentParent(field, pattern.subList(0, pattern.size() - 1));

        String key = finalSegment;
        if (dynamic) {
            String placeholder = placeholderName(finalSegment);
            validateDynamicKey(field.dynamicKeyGrammars().get(placeholder), dynamicKey);
            if (current.descriptor.reservedChildren().contains(dynamicKey)) {
                throw new IllegalArgumentException(
                        "Manifest key `" + dynamicKey + "` is reserved at `"
                                + current.concretePath + "`.");
            }
            key = dynamicKey;
        }

        ManifestPath concreteField = current.concretePath.child(key);
        if (SCHEMA.matchField(concreteField)
                        .filter(match -> match.descriptor() == field)
                        .isEmpty()) {
            throw new IllegalArgumentException(
                    "Manifest field handle `" + field.path()
                            + "` does not match concrete field `" + concreteField + "`.");
        }
        requireRenderedValue(field, renderedValue);
        current.emit(field, concreteField, renderKey(key), renderedValue);
    }

    private void requireCurrentParent(ManifestField field, List<String> parentPattern) {
        List<String> actual = current.concretePath.segments();
        if (parentPattern.size() != actual.size()) {
            throw wrongSection(field);
        }
        for (int index = 0; index < parentPattern.size(); index++) {
            String expected = parentPattern.get(index);
            String observed = actual.get(index);
            if (!isPlaceholder(expected) && !expected.equals(observed)) {
                throw wrongSection(field);
            }
            if (isPlaceholder(expected)) {
                String bound = current.bindings.get(placeholderName(expected));
                if (bound == null || !bound.equals(observed)) {
                    throw wrongSection(field);
                }
            }
        }
    }

    private IllegalArgumentException wrongSection(ManifestField field) {
        return new IllegalArgumentException(
                "Manifest field `" + field.path() + "` does not belong to section `"
                        + current.concretePath + "`.");
    }

    private void flushSection() {
        if (current == null || current.body.isEmpty()) {
            current = null;
            return;
        }
        EmissionOrder order = new EmissionOrder(
                current.descriptor.canonicalOrder(),
                current.descriptor.path(),
                current.concretePath);
        if (lastSection != null && order.compareTo(lastSection) < 0) {
            throw new IllegalStateException(
                    "Manifest section `" + current.concretePath
                            + "` is out of canonical order after `"
                            + lastSection.concretePath() + "`.");
        }
        if (!emittedSections.add(current.concretePath)) {
            throw new IllegalStateException(
                    "Manifest section `" + current.concretePath + "` was emitted more than once.");
        }
        if (!output.isEmpty()) {
            output.append('\n');
        }
        output.append('[').append(renderPath(current.concretePath)).append("]\n");
        output.append(current.body);
        lastSection = order;
        current = null;
    }

    private void requireOpen() {
        if (finished) {
            throw new IllegalStateException("Manifest TOML emission is already finished.");
        }
    }

    private static void requireRegistered(ManifestSection section) {
        Objects.requireNonNull(section, "Manifest section handle is required.");
        if (SCHEMA.section(section.path()).filter(registered -> registered == section).isEmpty()) {
            throw new IllegalArgumentException(
                    "Manifest section must be an exact registered schema handle: `"
                            + section.path() + "`.");
        }
    }

    private static void requireRegistered(ManifestField field) {
        Objects.requireNonNull(field, "Manifest field handle is required.");
        if (SCHEMA.field(field.path()).filter(registered -> registered == field).isEmpty()) {
            throw new IllegalArgumentException(
                    "Manifest field must be an exact registered schema handle: `"
                            + field.path() + "`.");
        }
    }

    private static void requireRenderedValue(ManifestField field, String value) {
        Objects.requireNonNull(value, "Rendered manifest value is required.");
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(
                    "Rendered value for `" + field.path()
                            + "` must be nonblank with no surrounding whitespace.");
        }
        if (value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(
                    "Rendered value for `" + field.path() + "` must use canonical LF line endings.");
        }
        if (field.formatting() == FormattingPolicy.ONE_LINE && value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(
                    "Rendered value for one-line field `" + field.path()
                            + "` must occupy one physical line.");
        }
        if (containsEmptyInlineTable(value)) {
            throw new IllegalArgumentException(
                    "Rendered value for `" + field.path() + "` must not contain an empty inline table.");
        }
    }

    private static boolean containsEmptyInlineTable(String value) {
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (quote != 0) {
                if (quote == '"' && character == '\\' && !escaped) {
                    escaped = true;
                    continue;
                }
                if (character == quote && !escaped) {
                    quote = 0;
                }
                escaped = false;
                continue;
            }
            if (character == '"' || character == '\'') {
                quote = character;
                continue;
            }
            if (character != '{') {
                continue;
            }
            int next = index + 1;
            while (next < value.length() && Character.isWhitespace(value.charAt(next))) {
                next++;
            }
            if (next < value.length() && value.charAt(next) == '}') {
                return true;
            }
        }
        return false;
    }

    private static ManifestPath bind(ManifestPath pattern, String placeholder, String value) {
        return new ManifestPath(pattern.segments().stream()
                .map(segment -> segment.equals("<" + placeholder + ">") ? value : segment)
                .toList());
    }

    private static void validateDynamicKey(ManifestDynamicKeyGrammar grammar, String value) {
        Objects.requireNonNull(grammar, "Manifest dynamic-key grammar is required.");
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

    private static String renderPath(ManifestPath path) {
        return path.segments().stream()
                .map(ManifestTomlEmitter::renderKey)
                .collect(java.util.stream.Collectors.joining("."));
    }

    private static String renderKey(String key) {
        if (BARE_KEY.matcher(key).matches()) {
            return key;
        }
        return ManifestTomlValueEncoder.quotedKey(key);
    }

    private static boolean isPlaceholder(String segment) {
        return segment.length() > 2 && segment.startsWith("<") && segment.endsWith(">");
    }

    private static String placeholderName(String segment) {
        return segment.substring(1, segment.length() - 1);
    }

    private record EmissionOrder(int canonicalOrder, ManifestPath descriptorPath, ManifestPath concretePath)
            implements Comparable<EmissionOrder> {
        @Override
        public int compareTo(EmissionOrder other) {
            int byOrder = Integer.compare(canonicalOrder, other.canonicalOrder);
            if (byOrder != 0) {
                return byOrder;
            }
            int byDescriptor = descriptorPath.compareTo(other.descriptorPath);
            return byDescriptor != 0 ? byDescriptor : ManifestModelValues.CODE_POINT_ORDER.compare(
                    concretePath.toString(), other.concretePath.toString());
        }
    }

    private static final class SectionState {
        private final ManifestSection descriptor;
        private final ManifestPath concretePath;
        private final Map<String, String> bindings;
        private final StringBuilder body = new StringBuilder();
        private final Set<ManifestPath> emittedFields = new HashSet<>();
        private EmissionOrder lastField;

        private SectionState(ManifestSection descriptor, ManifestPath concretePath, Map<String, String> bindings) {
            this.descriptor = descriptor;
            this.concretePath = concretePath;
            this.bindings = Map.copyOf(bindings);
        }

        private void emit(ManifestField field, ManifestPath concreteField, String renderedKey, String renderedValue) {
            EmissionOrder order = new EmissionOrder(
                    field.canonicalOrder(), field.path(), concreteField);
            if (lastField != null && order.compareTo(lastField) < 0) {
                throw new IllegalStateException(
                        "Manifest field `" + concreteField + "` is out of canonical order after `"
                                + lastField.concretePath() + "`.");
            }
            if (!emittedFields.add(concreteField)) {
                throw new IllegalStateException(
                        "Manifest field `" + concreteField + "` was emitted more than once.");
            }
            body.append(renderedKey).append(" = ").append(renderedValue).append('\n');
            lastField = order;
        }
    }
}
