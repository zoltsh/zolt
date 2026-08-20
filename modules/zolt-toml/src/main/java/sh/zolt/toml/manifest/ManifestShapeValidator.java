package sh.zolt.toml.manifest;

import static sh.zolt.toml.manifest.ManifestShapeText.acceptsInlineTable;
import static sh.zolt.toml.manifest.ManifestShapeText.canonicalHeader;
import static sh.zolt.toml.manifest.ManifestShapeText.dotted;
import static sh.zolt.toml.manifest.ManifestShapeText.mutableMessage;
import static sh.zolt.toml.manifest.ManifestShapeText.onePhysicalLine;
import static sh.zolt.toml.manifest.ManifestShapeText.sectionPath;
import static sh.zolt.toml.manifest.ManifestShapeText.spanIsOneLine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.tomlj.TomlTable;
import sh.zolt.toml.manifest.ManifestSchemaNavigator.Kind;
import sh.zolt.toml.manifest.ManifestSchemaNavigator.Resolution;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.FormattingPolicy;
import sh.zolt.toml.schema.ManifestField;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSchemaMatch;
import sh.zolt.toml.schema.ManifestSchemaRegistry;
import sh.zolt.toml.schema.ManifestSection;
import sh.zolt.toml.schema.MutationPolicy;
import sh.zolt.toml.schema.SectionKind;
import sh.zolt.toml.syntax.AssignmentSyntax;
import sh.zolt.toml.syntax.TableSyntax;

/** Validates the final manifest shape without constructing domain semantics. */
final class ManifestShapeValidator {
    private final ManifestSchemaRegistry registry;
    ManifestShapeValidator() {
        this(FinalManifestSchema.registry());
    }
    ManifestShapeValidator(ManifestSchemaRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    ValidatedManifestShape validate(ParsedManifestSyntax parsedSyntax) {
        Objects.requireNonNull(parsedSyntax, "parsedSyntax");
        String source = parsedSyntax.source();
        ManifestSyntax syntax = parsedSyntax.syntax();
        ManifestShapeValidationContext context = new ManifestShapeValidationContext(source, syntax, registry);
        validateExplicitTables(context);
        walkTable(parsedSyntax.parsed(), List.of(), context);
        context.diagnostics.throwIfAny();
        context.sections.sort(NODE_ORDER);
        context.fields.sort(FIELD_ORDER);
        return new ValidatedManifestShape(context.sections, context.fields);
    }

    private static void validateExplicitTables(ManifestShapeValidationContext context) {
        context.syntax.tables().stream()
                .filter(TableSyntax::explicit)
                .sorted(Comparator.comparingInt(table -> table.headerSpan().start()))
                .forEach(table -> validateExplicitTable(table, context));
    }

    private static void validateExplicitTable(TableSyntax table, ManifestShapeValidationContext context) {
        ManifestShapeSource source = context.sources.table(table.path());
        if (table.arrayTable()) {
            context.diagnostics.add(source,
                    "Array tables are not part of the Zolt manifest language; use an ordinary table header.");
            return;
        }
        if (table.path().size() > 3) {
            context.diagnostics.add(source, "Manifest table `" + sectionPath(table.path())
                    + "` exceeds the three-segment table budget.");
            return;
        }

        Optional<ManifestSchemaMatch<ManifestSection>> sectionMatch =
                context.navigator.sectionMatch(table.path());
        if (sectionMatch.isPresent()) {
            ManifestSchemaMatch<ManifestSection> match = sectionMatch.orElseThrow();
            Optional<String> reserved = context.navigator.reservedBinding(
                    match.descriptor().path(), match.bindings(), match.descriptor().reservedChildren());
            if (reserved.isPresent()) {
                context.fieldValidator.validateDynamicKeys(
                        match.descriptor().path().segments(),
                        match.bindings(),
                        match.descriptor().dynamicKeyGrammars(),
                        reserved,
                        source,
                        dotted(table.path()));
                return;
            }
        }

        Resolution resolution = context.navigator.resolve(table.path());
        if (resolution.kind() == Kind.FIELD) {
            ManifestField field = resolution.field().orElseThrow().descriptor();
            if (field.mutation() == MutationPolicy.REPLACE_ENTRY) {
                List<String> parent = context.navigator.mutableParent(field, table.path());
                context.diagnostics.add(source, mutableMessage(parent, table.path()));
            } else {
                context.diagnostics.add(source, "Manifest field `" + dotted(table.path())
                        + "` must be authored as an assignment, not a table header.");
            }
        } else if (resolution.kind() == Kind.UNKNOWN) {
            unknownSection(table.path(), source, context);
        }
    }

    private static void walkTable(TomlTable table, List<String> parent, ManifestShapeValidationContext context) {
        for (var entry : table.entrySet()) {
            ArrayList<String> actual = new ArrayList<>(parent);
            actual.add(entry.getKey());
            List<String> path = List.copyOf(actual);
            Object value = entry.getValue();
            Resolution resolution = context.navigator.resolve(path);
            switch (resolution.kind()) {
                case FIELD -> validateField(path, value, resolution.field().orElseThrow(), context);
                case SECTION -> validateSection(path, value, resolution.section().orElseThrow(), context);
                case STRUCTURAL_PREFIX -> validateStructuralPrefix(path, value, context);
                case UNKNOWN -> validateUnknown(path, value, context);
            }
        }
    }

    private static void validateField(
            List<String> path, Object value,
            ManifestSchemaMatch<ManifestField> match,
            ManifestShapeValidationContext context) {
        ManifestShapeSource source = value instanceof TomlTable
                        && !context.syntax.sourceIndex().explicitTablesAt(path).isEmpty()
                ? context.sources.table(path)
                : context.sources.field(path);
        ManifestField field = match.descriptor();
        boolean layoutValid = validateFieldLayout(path, value, field, source, context);
        boolean valueValid = context.fieldValidator.validate(match, value, source);
        if (layoutValid && valueValid) {
            context.fields.add(new ValidatedManifestField(
                    new ManifestPath(path), match, value, source));
        }
    }

    private static boolean validateFieldLayout(
            List<String> path,
            Object value,
            ManifestField field,
            ManifestShapeSource source,
            ManifestShapeValidationContext context) {
        if (source.origin() == ManifestShapeOrigin.EXPLICIT_TABLE) {
            if (!context.diagnostics.hasViolationAt(source.span().start())) {
                context.diagnostics.add(source, field.mutation() == MutationPolicy.REPLACE_ENTRY
                        ? mutableMessage(context.navigator.mutableParent(field, path), path)
                        : "Manifest field `" + dotted(path)
                                + "` must be authored as an assignment, not a table header.");
            }
            return false;
        }
        if (value instanceof TomlTable
                && acceptsInlineTable(field.valueKind())
                && source.origin() == ManifestShapeOrigin.IMPLICIT_TABLE) {
            context.diagnostics.add(source, "Manifest field `" + dotted(path)
                    + "` must use an inline-table value, not dotted child assignments.");
            return false;
        }
        if (field.mutation() == MutationPolicy.REPLACE_ENTRY
                && !isDirectMutableEntry(path, source, context)) {
            context.diagnostics.add(source,
                    mutableMessage(context.navigator.mutableParent(field, path), path));
            return false;
        }
        if (field.formatting() == FormattingPolicy.ONE_LINE
                && !onePhysicalLine(source, context.source)) {
            context.diagnostics.add(source, "Manifest field `" + dotted(path)
                    + "` must occupy one physical assignment line.");
            return false;
        }
        return true;
    }

    private static boolean isDirectMutableEntry(
            List<String> path,
            ManifestShapeSource source,
            ManifestShapeValidationContext context) {
        if (source.origin() != ManifestShapeOrigin.DIRECT_ASSIGNMENT
                || source.assignment().isEmpty()) {
            return false;
        }
        AssignmentSyntax assignment = source.assignment().orElseThrow();
        List<String> parent = path.subList(0, path.size() - 1);
        return assignment.tablePath().equals(parent)
                && assignment.keyPath().size() == 1
                && context.syntax.sourceIndex().explicitTablesAt(parent).stream()
                        .anyMatch(table -> canonicalHeader(parent, table, context.source))
                && spanIsOneLine(assignment.assignmentSpan(), context.source);
    }

    private static void validateSection(
            List<String> path,
            Object value,
            ManifestSchemaMatch<ManifestSection> match,
            ManifestShapeValidationContext context) {
        ManifestShapeSource source = value instanceof TomlTable
                ? context.sources.table(path)
                : context.sources.field(path);
        Optional<String> reserved = context.navigator.reservedBinding(
                match.descriptor().path(), match.bindings(), match.descriptor().reservedChildren());
        boolean keysValid = context.fieldValidator.validateDynamicKeys(
                match.descriptor().path().segments(),
                match.bindings(),
                match.descriptor().dynamicKeyGrammars(),
                reserved,
                source,
                dotted(path));
        if (!(value instanceof TomlTable child)) {
            context.diagnostics.add(source, "Manifest section `" + sectionPath(path)
                    + "` must be a table, not " + value.getClass().getSimpleName() + ".");
            return;
        }
        boolean sourceValid = validateSectionSource(path, child, match.descriptor(), source, context);
        if (keysValid && sourceValid) {
            context.sections.add(new ValidatedManifestSection(
                    new ManifestPath(path), Optional.of(match), source));
        }
        walkTable(child, path, context);
    }

    private static boolean validateSectionSource(
            List<String> path,
            TomlTable table,
            ManifestSection section,
            ManifestShapeSource source,
            ManifestShapeValidationContext context) {
        boolean valid = true;
        if (context.navigator.isMutableParent(path)
                && source.authoredTable()
                && source.origin() != ManifestShapeOrigin.EXPLICIT_TABLE) {
            context.diagnostics.add(source, mutableMessage(path, path));
            valid = false;
        }
        if (context.navigator.isMutableParent(path)
                && source.origin() == ManifestShapeOrigin.EXPLICIT_TABLE
                && source.table().filter(tableSyntax ->
                        canonicalHeader(path, tableSyntax, context.source)).isEmpty()) {
            context.diagnostics.add(source, "Mutable table `" + sectionPath(path)
                    + "` must use the exact canonical header `" + sectionPath(path) + "`.");
            valid = false;
        }
        if (path.equals(List.of("toolchain", "java"))
                && source.authoredTable()
                && !hasImmediateField(path, table, context)) {
            context.diagnostics.add(source, "Manifest table `[toolchain.java]` must contain at least "
                    + "one direct main-toolchain field; an implied parent may contain only `[toolchain.java.test]`.");
            valid = false;
        }
        if (path.equals(List.of("bom"))
                && source.authoredTable()
                && !hasAuthoredImmediateField(path, table, "members", context)) {
            context.diagnostics.add(source, "Manifest table `[bom]` must contain direct `members` "
                    + "when authored; omit `[bom]` when only versions or imports are declared.");
            valid = false;
        }
        if (table.isEmpty() && source.authoredTable() && section.kind() != SectionKind.COLLECTION) {
            context.diagnostics.add(source, "Manifest table `" + sectionPath(path)
                    + "` must not be empty; only collection tables may be explicitly empty.");
            valid = false;
        }
        return valid;
    }

    private static boolean hasImmediateField(
            List<String> path, TomlTable table, ManifestShapeValidationContext context) {
        return table.keySet().stream().anyMatch(key -> {
            ArrayList<String> child = new ArrayList<>(path);
            child.add(key);
            return context.navigator.resolve(child).kind() == Kind.FIELD;
        });
    }

    private static boolean hasAuthoredImmediateField(
            List<String> path,
            TomlTable table,
            String field,
            ManifestShapeValidationContext context) {
        if (!table.keySet().contains(field)) {
            return false;
        }
        ArrayList<String> child = new ArrayList<>(path);
        child.add(field);
        return context.sources.field(child).assignment().isPresent();
    }

    private static void validateStructuralPrefix(
            List<String> path, Object value, ManifestShapeValidationContext context) {
        ManifestShapeSource source = value instanceof TomlTable
                ? context.sources.table(path)
                : context.sources.field(path);
        if (!(value instanceof TomlTable child)) {
            context.diagnostics.add(source, "Manifest namespace `" + dotted(path)
                    + "` must contain tables.");
            return;
        }
        boolean valid = !(child.isEmpty() && source.authoredTable());
        if (!valid) {
            context.diagnostics.add(source, "Manifest grouping table `" + sectionPath(path)
                    + "` must not be empty.");
        } else {
            context.sections.add(new ValidatedManifestSection(
                    new ManifestPath(path), Optional.empty(), source));
        }
        walkTable(child, path, context);
    }

    private static void validateUnknown(
            List<String> path, Object value, ManifestShapeValidationContext context) {
        ManifestShapeSource source = value instanceof TomlTable
                ? context.sources.table(path)
                : context.sources.field(path);
        if (value instanceof TomlTable) {
            unknownSection(path, source, context);
        } else {
            Optional<String> suggestion = context.navigator.suggestField(path);
            if (path.size() == 1 && suggestion.isEmpty()) {
                suggestion = context.navigator.suggestSection(path);
            }
            String message = "Unknown manifest field `" + dotted(path) + "`.";
            if (suggestion.isPresent()) {
                ArrayList<String> corrected = new ArrayList<>(path);
                corrected.set(corrected.size() - 1, suggestion.orElseThrow());
                message += " Did you mean `" + dotted(corrected) + "`?";
            }
            context.diagnostics.add(source, message);
        }
    }

    private static void unknownSection(
            List<String> path, ManifestShapeSource source, ManifestShapeValidationContext context) {
        Optional<String> suggestion = context.navigator.suggestSection(path);
        String message = "Unknown manifest section `" + sectionPath(path) + "`.";
        if (suggestion.isPresent()) {
            ArrayList<String> corrected = new ArrayList<>(path);
            corrected.set(corrected.size() - 1, suggestion.orElseThrow());
            message += " Did you mean `" + sectionPath(corrected) + "`?";
        }
        context.diagnostics.add(source, message);
    }

    private static final Comparator<ValidatedManifestSection> NODE_ORDER = Comparator
            .comparingInt((ValidatedManifestSection value) -> value.source().span().start())
            .thenComparing(ValidatedManifestSection::path);
    private static final Comparator<ValidatedManifestField> FIELD_ORDER = Comparator
            .comparingInt((ValidatedManifestField value) -> value.source().span().start())
            .thenComparing(ValidatedManifestField::path);
}
