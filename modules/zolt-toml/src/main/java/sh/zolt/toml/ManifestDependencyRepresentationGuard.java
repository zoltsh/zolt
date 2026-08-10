package sh.zolt.toml;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlPosition;
import org.tomlj.TomlTable;

/** Rejects dependency forms that cannot be patched safely as one physical assignment. */
final class ManifestDependencyRepresentationGuard {
    private ManifestDependencyRepresentationGuard() {
    }

    static void requireEditable(
            TomlParseResult parsed,
            ManifestSourceText.Lines lines,
            List<String> tablePath,
            TomlTable sourceTable,
            Function<String, String> canonicalAssignment) {
        if (sourceTable == null || !isDependencyTable(tablePath)) {
            return;
        }
        for (String key : sourceTable.keySet()) {
            TomlPosition position = sourceTable.inputPositionOf(List.of(key));
            if (position == null) {
                continue;
            }
            String rewrite = canonicalAssignment.apply(key);
            if (rewrite == null) {
                rewrite = quoteKey(key) + " = { version = \"1.0.0\" }";
            }
            List<String> nestedPath = new ArrayList<>(tablePath);
            nestedPath.add(key);
            if (hasHeader(parsed, lines, nestedPath)) {
                throw unsupported(tablePath, key, "long-form dependency table", rewrite);
            }
            String assignmentLine = lines.line(position.line());
            String header = "[" + String.join(".", tablePath) + "]\n";
            if (Toml.parse(header + assignmentLine + "\n").hasErrors()) {
                throw unsupported(tablePath, key, "multiline dependency value", rewrite);
            }
        }
    }

    private static boolean hasHeader(
            TomlParseResult parsed,
            ManifestSourceText.Lines lines,
            List<String> expectedPath) {
        TomlPosition position = parsed.inputPositionOf(expectedPath);
        if (position == null) {
            return false;
        }
        String candidate = lines.line(position.line()).stripLeading();
        if (!candidate.startsWith("[") || candidate.startsWith("[[")) {
            return false;
        }
        TomlParseResult probe = Toml.parse(candidate + "\n__zolt_manifest_probe__ = true\n");
        TomlTable table = probe.hasErrors() ? null : probe.getTable(expectedPath);
        return table != null && Boolean.TRUE.equals(table.get("__zolt_manifest_probe__"));
    }

    private static boolean isDependencyTable(List<String> tablePath) {
        return !tablePath.equals(List.of("versions"));
    }

    private static ZoltConfigException unsupported(
            List<String> tablePath,
            String key,
            String representation,
            String rewrite) {
        return new ZoltConfigException(
                "Could not safely edit ["
                        + String.join(".", tablePath)
                        + "]."
                        + key
                        + " because it uses a "
                        + representation
                        + ". Rewrite it in the canonical section as `"
                        + rewrite
                        + "` before retrying. No changes were written.");
    }

    private static String quoteKey(String key) {
        return "\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
