package sh.zolt.toml.manifest;

import java.util.Comparator;
import java.util.List;
import org.tomlj.TomlTable;
import sh.zolt.toml.schema.ManifestObjectMember;
import sh.zolt.toml.schema.ManifestObjectShape;

/** Closed member, value-kind, and presence checks for one inline object. */
final class ManifestShapeObjectValidator {
    private final ManifestShapeDiagnostics diagnostics;

    ManifestShapeObjectValidator(ManifestShapeDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    boolean validate(
            ManifestObjectShape shape,
            TomlTable table,
            ManifestShapeSource source,
            String path) {
        boolean valid = validateMembers(shape, table, source, path);
        valid &= validateRequired(shape, table, source, path);
        valid &= validatePresence(shape, table, source, path);
        return valid;
    }

    private boolean validateMembers(
            ManifestObjectShape shape,
            TomlTable table,
            ManifestShapeSource source,
            String path) {
        boolean valid = true;
        for (var entry : table.entrySet()) {
            String memberPath = path + "." + entry.getKey();
            var member = shape.member(entry.getKey());
            if (member.isEmpty()) {
                ManifestObjectMember suggestion = nearest(entry.getKey(), shape.members());
                diagnostics.add(source, "Unknown manifest field `" + memberPath
                        + "`. Did you mean `" + path + "." + suggestion.name() + "`?");
                valid = false;
            } else if (!ManifestShapeValueKinds.matches(
                    member.orElseThrow().valueKind(), entry.getValue())) {
                ManifestObjectMember descriptor = member.orElseThrow();
                diagnostics.add(source, "Invalid value for `" + memberPath + "`: expected "
                        + ManifestShapeValueKinds.expected(descriptor.valueKind()) + " but found "
                        + ManifestShapeValueKinds.actual(entry.getValue()) + ".");
                valid = false;
            }
        }
        return valid;
    }

    private boolean validateRequired(
            ManifestObjectShape shape,
            TomlTable table,
            ManifestShapeSource source,
            String path) {
        boolean valid = true;
        for (ManifestObjectMember member : shape.members()) {
            if (member.required() && !table.keySet().contains(member.name())) {
                diagnostics.add(source, "Missing required inline-object field `"
                        + path + "." + member.name() + "`.");
                valid = false;
            }
        }
        return valid;
    }

    private boolean validatePresence(
            ManifestObjectShape shape,
            TomlTable table,
            ManifestShapeSource source,
            String path) {
        boolean valid = true;
        for (ManifestObjectShape.PresenceGroup group : shape.presenceGroups()) {
            long present = group.members().stream()
                    .filter(member -> table.keySet().contains(member.name()))
                    .count();
            boolean accepted = switch (group.rule()) {
                case AT_LEAST_ONE -> present >= 1;
                case EXACTLY_ONE -> present == 1;
            };
            if (!accepted) {
                String requirement = group.rule() == ManifestObjectShape.PresenceRule.AT_LEAST_ONE
                        ? "at least one"
                        : "exactly one";
                diagnostics.add(source, "Inline object `" + path + "` must declare "
                        + requirement + " of " + memberNames(group.members()) + ".");
                valid = false;
            }
        }
        return valid;
    }

    private static ManifestObjectMember nearest(
            String observed,
            List<ManifestObjectMember> members) {
        return members.stream()
                .min(Comparator.comparingInt((ManifestObjectMember member) ->
                                distance(observed, member.name()))
                        .thenComparingInt(ManifestObjectMember::canonicalOrder)
                        .thenComparing(ManifestObjectMember::name))
                .orElseThrow();
    }

    private static String memberNames(List<ManifestObjectMember> members) {
        return members.stream()
                .map(member -> "`" + member.name() + "`")
                .collect(java.util.stream.Collectors.joining(" or "));
    }

    private static int distance(String left, String right) {
        int[] a = left.codePoints().toArray();
        int[] b = right.codePoints().toArray();
        int[] previous = new int[b.length + 1];
        for (int index = 0; index <= b.length; index++) {
            previous[index] = index;
        }
        for (int row = 1; row <= a.length; row++) {
            int[] current = new int[b.length + 1];
            current[0] = row;
            for (int column = 1; column <= b.length; column++) {
                int substitution = previous[column - 1]
                        + (a[row - 1] == b[column - 1] ? 0 : 1);
                current[column] = Math.min(
                        Math.min(previous[column] + 1, current[column - 1] + 1),
                        substitution);
            }
            previous = current;
        }
        return previous[b.length];
    }
}
