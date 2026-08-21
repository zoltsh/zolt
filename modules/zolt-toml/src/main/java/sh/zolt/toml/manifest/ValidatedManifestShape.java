package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSchemaMatch;
import sh.zolt.toml.schema.ManifestSchemaRegistry;
import sh.zolt.toml.schema.ManifestSection;
import sh.zolt.toml.syntax.AssignmentSyntax;
import sh.zolt.toml.syntax.ManifestSourceIndex;
import sh.zolt.toml.syntax.SourceSpan;
import sh.zolt.toml.syntax.TableSyntax;

/** Validated raw manifest nodes consumed by final semantic construction. */
record ValidatedManifestShape(
        List<ValidatedManifestSection> sections,
        List<ValidatedManifestField> fields) {
    ValidatedManifestShape {
        sections = List.copyOf(Objects.requireNonNull(sections, "Validated sections are required."));
        fields = List.copyOf(Objects.requireNonNull(fields, "Validated fields are required."));
    }
}
record ValidatedManifestSection(
        ManifestPath path,
        Optional<ManifestSchemaMatch<ManifestSection>> schema,
        ManifestShapeSource source) {
    ValidatedManifestSection {
        Objects.requireNonNull(path, "Validated section path is required.");
        schema = Objects.requireNonNull(schema, "Validated section schema must not be null.");
        Objects.requireNonNull(source, "Validated section source is required.");
    }
}

record ValidatedManifestField(
        ManifestPath path,
        ManifestSchemaMatch<ManifestField> schema,
        Object rawValue,
        ManifestShapeSource source) {
    ValidatedManifestField {
        Objects.requireNonNull(path, "Validated field path is required.");
        Objects.requireNonNull(schema, "Validated field schema is required.");
        Objects.requireNonNull(rawValue, "Validated field value is required.");
        Objects.requireNonNull(source, "Validated field source is required.");
    }
}

/** Best available exact source evidence for one semantic manifest node. */
record ManifestShapeSource(
        ManifestShapeOrigin origin,
        SourceSpan span,
        Optional<AssignmentSyntax> assignment,
        Optional<TableSyntax> table) {
    ManifestShapeSource {
        Objects.requireNonNull(origin, "Manifest shape origin is required.");
        Objects.requireNonNull(span, "Manifest shape source span is required.");
        assignment = Objects.requireNonNull(
                assignment, "Manifest shape assignment evidence must not be null.");
        table = Objects.requireNonNull(table, "Manifest shape table evidence must not be null.");
    }

    boolean authoredTable() {
        return origin == ManifestShapeOrigin.EXPLICIT_TABLE
                || origin == ManifestShapeOrigin.INLINE_PARENT;
    }
}

enum ManifestShapeOrigin {
    DIRECT_ASSIGNMENT,
    INLINE_PARENT,
    EXPLICIT_TABLE,
    IMPLICIT_TABLE
}

/** Joins Tomlj's semantic tree back to the source nodes captured by Zolt's scanner. */
final class ManifestShapeSourceResolver {
    private static final Comparator<TableSyntax> TABLE_SOURCE =
            Comparator.comparingInt(table -> table.headerSpan().start());
    private static final Comparator<AssignmentSyntax> ASSIGNMENT_SOURCE =
            Comparator.comparingInt(assignment -> assignment.keySpan().start());

    private final ManifestSourceIndex index;
    private final int sourceLength;

    ManifestShapeSourceResolver(ManifestSyntax syntax, int sourceLength) {
        this.index = syntax.sourceIndex();
        this.sourceLength = sourceLength;
    }

    ManifestShapeSource field(List<String> path) {
        Optional<AssignmentSyntax> exact = firstAssignment(path);
        if (exact.isPresent()) {
            AssignmentSyntax assignment = exact.orElseThrow();
            return assignmentSource(ManifestShapeOrigin.DIRECT_ASSIGNMENT, assignment);
        }
        Optional<AssignmentSyntax> ancestor = longestAssignmentAncestor(path);
        if (ancestor.isPresent()) {
            AssignmentSyntax assignment = ancestor.orElseThrow();
            return assignmentSource(ManifestShapeOrigin.INLINE_PARENT, assignment);
        }
        return implicit(path);
    }

    ManifestShapeSource table(List<String> path) {
        Optional<TableSyntax> explicit = index.explicitTablesAt(path).stream()
                .min(TABLE_SOURCE);
        if (explicit.isPresent()) {
            TableSyntax table = explicit.orElseThrow();
            return new ManifestShapeSource(
                    ManifestShapeOrigin.EXPLICIT_TABLE,
                    table.headerSpan(),
                    Optional.empty(),
                    Optional.of(table));
        }
        Optional<AssignmentSyntax> exact = firstAssignment(path);
        if (exact.isPresent()) {
            AssignmentSyntax assignment = exact.orElseThrow();
            return assignmentSource(ManifestShapeOrigin.INLINE_PARENT, assignment);
        }
        Optional<AssignmentSyntax> ancestor = longestAssignmentAncestor(path);
        if (ancestor.isPresent()) {
            AssignmentSyntax assignment = ancestor.orElseThrow();
            return assignmentSource(ManifestShapeOrigin.INLINE_PARENT, assignment);
        }
        return implicit(path);
    }

    private ManifestShapeSource implicit(List<String> path) {
        int offset = earliestDescendantOffset(path).orElse(0);
        return new ManifestShapeSource(
                ManifestShapeOrigin.IMPLICIT_TABLE,
                SourceSpan.emptyAt(offset),
                Optional.empty(),
                Optional.empty());
    }

    private Optional<AssignmentSyntax> firstAssignment(List<String> path) {
        return index.assignmentsAt(path).stream().min(ASSIGNMENT_SOURCE);
    }

    private Optional<AssignmentSyntax> longestAssignmentAncestor(List<String> path) {
        return index.assignments().stream()
                .filter(assignment -> isStrictPrefix(assignment.fullPath(), path))
                .max(Comparator.comparingInt((AssignmentSyntax value) -> value.fullPath().size())
                        .thenComparing(ASSIGNMENT_SOURCE.reversed()));
    }

    private Optional<Integer> earliestDescendantOffset(List<String> path) {
        return java.util.stream.Stream.concat(
                        index.assignments().stream()
                                .filter(value -> isPrefix(path, value.fullPath()))
                                .map(value -> value.keySpan().start()),
                        index.tables().stream()
                                .filter(TableSyntax::explicit)
                                .filter(value -> isPrefix(path, value.path()))
                                .map(value -> value.headerSpan().start()))
                .filter(offset -> offset >= 0 && offset <= sourceLength)
                .min(Integer::compareTo);
    }

    private static ManifestShapeSource assignmentSource(
            ManifestShapeOrigin origin,
            AssignmentSyntax assignment) {
        return new ManifestShapeSource(
                origin,
                assignment.assignmentSpan(),
                Optional.of(assignment),
                Optional.empty());
    }

    private static boolean isStrictPrefix(List<String> prefix, List<String> path) {
        return prefix.size() < path.size() && isPrefix(prefix, path);
    }

    private static boolean isPrefix(List<String> prefix, List<String> path) {
        return prefix.size() <= path.size()
                && prefix.equals(path.subList(0, prefix.size()));
    }
}

/** Shared state for one fail-closed manifest-shape validation pass. */
final class ManifestShapeValidationContext {
    final String source;
    final ManifestSyntax syntax;
    final ManifestSchemaNavigator navigator;
    final ManifestShapeDiagnostics diagnostics = new ManifestShapeDiagnostics();
    final ManifestShapeSourceResolver sources;
    final ManifestShapeFieldValidator fieldValidator;
    final List<ValidatedManifestSection> sections = new ArrayList<>();
    final List<ValidatedManifestField> fields = new ArrayList<>();

    ManifestShapeValidationContext(
            String source,
            ManifestSyntax syntax,
            ManifestSchemaRegistry registry) {
        if (!syntax.matchesSource(source)) {
            throw new ZoltConfigException(
                    "Manifest source does not match its parsed syntax; shape validation failed closed.");
        }
        this.source = source;
        this.syntax = syntax;
        navigator = new ManifestSchemaNavigator(registry);
        sources = new ManifestShapeSourceResolver(syntax, source.length());
        fieldValidator = new ManifestShapeFieldValidator(
                registry.symbols(), navigator, diagnostics);
    }
}

/** Deterministic source-ordered shape failures. */
final class ManifestShapeDiagnostics {
    private final List<Violation> violations = new ArrayList<>();
    private int sequence;

    void add(ManifestShapeSource source, String message) {
        add(source.span().start(), message);
    }

    void add(SourceSpan span, String message) {
        add(span.start(), message);
    }

    void add(int offset, String message) {
        violations.add(new Violation(Math.max(0, offset), sequence++, message));
    }

    boolean hasViolationAt(int offset) {
        return violations.stream().anyMatch(value -> value.offset() == offset);
    }

    void throwIfAny() {
        violations.stream()
                .min(Comparator.comparingInt(Violation::offset)
                        .thenComparingInt(Violation::sequence))
                .ifPresent(violation -> {
                    throw new ZoltConfigException(violation.message());
                });
    }

    private record Violation(int offset, int sequence, String message) {
    }
}

/** Exact final-registry identity checks for validated fields, sections, and handles. */
final class ManifestSchemaEvidence {
    private static final ManifestSchemaRegistry REGISTRY = FinalManifestSchema.registry();

    private ManifestSchemaEvidence() {
    }

    static ManifestField validatedField(ValidatedManifestField field) {
        Objects.requireNonNull(field, "Validated manifest field is required.");
        ManifestField descriptor = field.schema().descriptor();
        ManifestField registered = REGISTRY.field(descriptor.path()).orElseThrow(() ->
                new IllegalStateException(
                        "Validated manifest field uses an unregistered descriptor `"
                                + descriptor.path() + "`."));
        ManifestSchemaMatch<ManifestField> rematched =
                REGISTRY.matchField(field.path()).orElseThrow(() ->
                        new IllegalStateException(
                                "Validated manifest field `" + field.path()
                                        + "` does not match the final schema."));
        if (registered != descriptor
                || rematched.descriptor() != descriptor
                || !rematched.bindings().equals(field.schema().bindings())) {
            throw new IllegalStateException(
                    "Validated manifest field `" + field.path()
                            + "` does not use its exact registered schema match.");
        }
        return descriptor;
    }

    static ManifestSection validatedSection(ValidatedManifestSection section) {
        Objects.requireNonNull(section, "Validated manifest section is required.");
        ManifestSection descriptor = section.schema()
                .orElseThrow(() -> new IllegalStateException(
                        "Validated manifest section `" + section.path()
                                + "` has no registered schema match."))
                .descriptor();
        ManifestSection registered = REGISTRY.section(descriptor.path()).orElseThrow(() ->
                new IllegalStateException(
                        "Validated manifest section uses an unregistered descriptor `"
                                + descriptor.path() + "`."));
        ManifestSchemaMatch<ManifestSection> rematched =
                REGISTRY.matchSection(section.path()).orElseThrow(() ->
                        new IllegalStateException(
                                "Validated manifest section `" + section.path()
                                        + "` does not match the final schema."));
        if (registered != descriptor
                || rematched.descriptor() != descriptor
                || !rematched.bindings().equals(section.schema().orElseThrow().bindings())) {
            throw new IllegalStateException(
                    "Validated manifest section `" + section.path()
                            + "` does not use its exact registered schema match.");
        }
        return descriptor;
    }

    static ManifestField fieldHandle(ManifestField handle) {
        Objects.requireNonNull(handle, "Manifest field handle is required.");
        ManifestField registered = REGISTRY.field(handle.path()).orElseThrow(() ->
                new IllegalArgumentException(
                        "Manifest field handle `" + handle.path() + "` is not registered."));
        if (registered != handle) {
            throw new IllegalArgumentException(
                    "Manifest field access requires the exact registered handle `"
                            + handle.path() + "`.");
        }
        return registered;
    }

    static ManifestSection sectionHandle(ManifestPath handle) {
        Objects.requireNonNull(handle, "Manifest section handle is required.");
        ManifestSection registered = REGISTRY.section(handle).orElseThrow(() ->
                new IllegalArgumentException(
                        "Manifest section handle `[" + handle + "]` is not registered."));
        if (registered.path() != handle) {
            throw new IllegalArgumentException(
                    "Manifest section access requires the exact registered path `["
                            + handle + "]`.");
        }
        return registered;
    }

    static boolean hasRegisteredSection(ManifestPath path) {
        return REGISTRY.matchSection(path).isPresent();
    }
}
