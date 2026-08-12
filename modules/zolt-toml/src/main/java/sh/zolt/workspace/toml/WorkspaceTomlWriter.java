package sh.zolt.workspace.toml;

import sh.zolt.toml.AtomicManifestWriter;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.WorkspaceConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class WorkspaceTomlWriter {
    public void write(Path path, WorkspaceConfig config) {
        try {
            Files.writeString(path, write(config));
        } catch (IOException exception) {
            throw new ZoltConfigException(
                    "Could not write zolt.toml at " + path + ". Check that the directory exists and is writable.");
        }
    }

    public String write(WorkspaceConfig config) {
        StringBuilder toml = new StringBuilder();
        toml.append("[workspace]\n");
        toml.append("name = \"").append(escape(config.name())).append("\"\n");
        toml.append("members = ").append(stringArray(config.members())).append("\n");
        if (!config.defaultMembers().isEmpty()) {
            toml.append("defaultMembers = ").append(stringArray(config.defaultMembers())).append("\n");
        }
        writeStringMap(toml, "repositories", config.repositories());
        writeStringMap(toml, "platforms", config.platforms());
        return toml.toString();
    }

    public WorkspaceManifestDocument patchDocument(
            WorkspaceManifestDocument document,
            WorkspaceConfig updated) {
        String patched = WorkspaceManifestPatcher.patchPlatforms(
                document.source(), document.config(), updated);
        WorkspaceConfigParser parser = new WorkspaceConfigParser();
        WorkspaceConfig reparsed = document.rootConfig()
                ? parser.parseRootConfig(patched)
                : parser.parse(patched);
        if (!updated.equals(reparsed)) {
            throw new ZoltConfigException(
                    "Could not safely edit the workspace manifest because the patched source did not match the requested configuration. No changes were written.");
        }
        return new WorkspaceManifestDocument(patched, reparsed, document.rootConfig());
    }

    public void writePrepared(
            Path path,
            WorkspaceManifestDocument original,
            WorkspaceManifestDocument edited) {
        AtomicManifestWriter.writePrepared(path, original.source(), edited.source());
    }

    private static void writeStringMap(StringBuilder toml, String section, Map<String, String> values) {
        if (values.isEmpty()) {
            return;
        }
        toml.append("\n[").append(section).append("]\n");
        values.forEach((key, value) -> toml
                .append('"')
                .append(escape(key))
                .append("\" = \"")
                .append(escape(value))
                .append("\"\n"));
    }

    private static String stringArray(List<String> values) {
        if (values.size() <= 1) {
            return inlineStringArray(values);
        }
        StringBuilder array = new StringBuilder("[\n");
        for (String value : values) {
            array.append("    \"").append(escape(value)).append("\",\n");
        }
        return array.append(']').toString();
    }

    private static String inlineStringArray(List<String> values) {
        StringBuilder array = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                array.append(", ");
            }
            array.append('"').append(escape(values.get(index))).append('"');
        }
        return array.append(']').toString();
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
