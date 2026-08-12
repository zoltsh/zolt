package sh.zolt.workspace.toml;

import sh.zolt.toml.ToolchainSectionCodec;
import sh.zolt.toml.RepositorySectionCodec;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.project.RepositoryCredentialSettings;
import sh.zolt.project.RepositorySettings;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.WorkspaceConfigException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

public final class WorkspaceConfigParser {
    public static final String WORKSPACE_FILE = "zolt-workspace.toml";
    public static final String ROOT_CONFIG_FILE = "zolt.toml";

    private static final Set<String> TOP_LEVEL_SECTIONS =
            Set.of("workspace", "repositories", "repositoryCredentials", "platforms", "toolchain");
    private static final Set<String> ROOT_TOP_LEVEL_SECTIONS = Set.of(
            "project",
            "repositories",
            "repositoryCredentials",
            "versions",
            "platforms",
            "dependencyPolicy",
            "dependencyConstraints",
            "api",
            "dependencies",
            "runtime",
            "provided",
            "dev",
            "annotationProcessors",
            "test",
            "integrationTest",
            "build",
            "resources",
            "generated",
            "compiler",
            "package",
            "publish",
            "framework",
            "native",
            "toolchain",
            "workspace",
            "coverage",
            "commands");
    private static final Set<String> WORKSPACE_KEYS = Set.of("name", "members", "defaultMembers");

    public WorkspaceConfig parse(Path path) {
        try {
            return parse(Toml.parse(path), WORKSPACE_FILE, TOP_LEVEL_SECTIONS);
        } catch (IOException exception) {
            throw new WorkspaceConfigException(
                    "Could not read zolt-workspace.toml at " + path + ". Check that the file exists and is readable.");
        }
    }

    public WorkspaceConfig parseRootConfig(Path path) {
        try {
            return parse(Toml.parse(path), ROOT_CONFIG_FILE, ROOT_TOP_LEVEL_SECTIONS);
        } catch (IOException exception) {
            throw new WorkspaceConfigException(
                    "Could not read zolt.toml at " + path + ". Check that the file exists and is readable.");
        }
    }

    public WorkspaceConfig parseRootConfig(String content) {
        return parse(Toml.parse(content), ROOT_CONFIG_FILE, ROOT_TOP_LEVEL_SECTIONS);
    }

    public WorkspaceManifestDocument parseWorkspaceDocument(Path path) {
        Path filename = path.getFileName();
        boolean rootConfig = filename != null && ROOT_CONFIG_FILE.equals(filename.toString());
        try {
            String source = Files.readString(path);
            WorkspaceConfig config = rootConfig ? parseRootConfig(source) : parse(source);
            return new WorkspaceManifestDocument(source, config, rootConfig);
        } catch (IOException exception) {
            throw new WorkspaceConfigException(
                    "Could not read workspace manifest at " + path + ". Check that it exists and is readable.");
        }
    }

    /**
     * Returns whether a root project contains the intentionally retained, inert workspace domain.
     * Invalid workspace configuration is never classified as inert.
     */
    public boolean isRetainedEmptyRootWorkspace(Path path) {
        try {
            return isRetainedEmptyRootWorkspace(Toml.parse(path));
        } catch (IOException exception) {
            throw new WorkspaceConfigException(
                    "Could not read zolt.toml at " + path + ". Check that the file exists and is readable.");
        }
    }

    public boolean hasWorkspaceSection(Path path) {
        try {
            return hasWorkspaceSection(Toml.parse(path));
        } catch (IOException exception) {
            throw new WorkspaceConfigException(
                    "Could not read zolt.toml at " + path + ". Check that the file exists and is readable.");
        }
    }

    public boolean hasWorkspaceSection(String content) {
        return hasWorkspaceSection(Toml.parse(content));
    }

    public WorkspaceConfig parse(String content) {
        return parse(Toml.parse(content), WORKSPACE_FILE, TOP_LEVEL_SECTIONS);
    }

    private static boolean hasWorkspaceSection(TomlParseResult result) {
        if (result.hasErrors()) {
            throw new WorkspaceConfigException(parseErrorMessage(result, ROOT_CONFIG_FILE));
        }
        return result.getTable("workspace") != null;
    }

    private boolean isRetainedEmptyRootWorkspace(TomlParseResult result) {
        if (result.hasErrors() || result.getTable("project") == null) {
            return false;
        }
        try {
            WorkspaceConfig config = parse(
                    result,
                    ROOT_CONFIG_FILE,
                    ROOT_TOP_LEVEL_SECTIONS,
                    true);
            return config.members().isEmpty() && config.defaultMembers().isEmpty();
        } catch (WorkspaceConfigException exception) {
            return false;
        }
    }

    private WorkspaceConfig parse(
            TomlParseResult result,
            String sourceName,
            Set<String> allowedTopLevelSections) {
        return parse(result, sourceName, allowedTopLevelSections, false);
    }

    private WorkspaceConfig parse(
            TomlParseResult result,
            String sourceName,
            Set<String> allowedTopLevelSections,
            boolean allowEmptyMembers) {
        if (result.hasErrors()) {
            throw new WorkspaceConfigException(parseErrorMessage(result, sourceName));
        }
        validateTopLevelSections(result, sourceName, allowedTopLevelSections);
        validateToolchain(result, sourceName);

        TomlTable workspaceTable = requiredTable(result, "workspace", sourceName);
        validateKeys("workspace", workspaceTable, WORKSPACE_KEYS, sourceName);

        try {
            Map<String, RepositorySettings> repositorySettings =
                    RepositorySectionCodec.repositorySettings(
                            optionalTable(result, "repositories"));
            Map<String, RepositoryCredentialSettings> repositoryCredentials =
                    RepositorySectionCodec.repositoryCredentials(
                            optionalTable(result, "repositoryCredentials"));
            RepositorySectionCodec.validateRepositoryCredentialReferences(
                    repositorySettings,
                    repositoryCredentials);
            return new WorkspaceConfig(
                    requiredString(workspaceTable, "workspace", "name", sourceName),
                    requiredStringList(
                            workspaceTable,
                            "workspace",
                            "members",
                            sourceName,
                            allowEmptyMembers),
                    stringListOrDefault(
                            workspaceTable,
                            "workspace",
                            "defaultMembers",
                            List.of(),
                            sourceName),
                    RepositorySectionCodec.repositoryUrls(repositorySettings),
                    stringMap(optionalTable(result, "platforms"), "platforms", sourceName),
                    repositorySettings,
                    repositoryCredentials);
        } catch (ZoltConfigException exception) {
            throw new WorkspaceConfigException(
                    exception.getMessage().replace("in zolt.toml", "in " + sourceName));
        }
    }

    private static String parseErrorMessage(TomlParseResult result, String sourceName) {
        TomlParseError firstError = result.errors().getFirst();
        return "Could not parse " + sourceName + ". Fix the TOML syntax near "
                + firstError.position()
                + ": "
                + firstError.getMessage();
    }

    private static void validateTopLevelSections(
            TomlParseResult result,
            String sourceName,
            Set<String> allowedTopLevelSections) {
        for (String key : result.keySet()) {
            if (!allowedTopLevelSections.contains(key)) {
                throw new WorkspaceConfigException(
                        "Unknown top-level section [" + key + "] in " + sourceName + ". Remove it or check the spelling.");
            }
        }
    }

    private static void validateKeys(
            String section,
            TomlTable table,
            Set<String> allowedKeys,
            String sourceName) {
        for (String key : table.keySet()) {
            if (!allowedKeys.contains(key)) {
                throw new WorkspaceConfigException(
                        "Unknown field [" + section + "]." + key + " in " + sourceName + ". Remove it or check the spelling.");
            }
        }
    }

    private static void validateToolchain(TomlParseResult result, String sourceName) {
        try {
            ToolchainSectionCodec.parseZoltVersion(result, sourceName);
        } catch (sh.zolt.toml.ZoltConfigException exception) {
            throw new WorkspaceConfigException(exception.getMessage());
        }
    }

    private static TomlTable requiredTable(TomlParseResult result, String section, String sourceName) {
        TomlTable table = result.getTable(section);
        if (table == null) {
            throw new WorkspaceConfigException("Missing required section [" + section + "] in " + sourceName + ".");
        }
        return table;
    }

    private static TomlTable optionalTable(TomlParseResult result, String section) {
        return result.getTable(section);
    }

    private static String requiredString(TomlTable table, String section, String key, String sourceName) {
        String value = table.getString(key);
        if (value == null || value.isBlank()) {
            throw new WorkspaceConfigException(
                    "Missing required field [" + section + "]." + key + " in " + sourceName + ". Add a non-empty string value.");
        }
        return value;
    }

    private static List<String> requiredStringList(
            TomlTable table,
            String section,
            String key,
            String sourceName,
            boolean allowEmpty) {
        List<String> values = stringListOrDefault(table, section, key, null, sourceName);
        if (values == null) {
            throw new WorkspaceConfigException(
                    "Missing required field [" + section + "]." + key + " in " + sourceName + ". Add an array of member paths.");
        }
        if (values.isEmpty() && !allowEmpty) {
            throw new WorkspaceConfigException(
                    "Invalid value for [" + section + "]." + key + " in " + sourceName + ". Add at least one member path.");
        }
        return values;
    }

    private static List<String> stringListOrDefault(
            TomlTable table,
            String section,
            String key,
            List<String> defaultValue,
            String sourceName) {
        Object rawValue = table.get(List.of(key));
        if (rawValue == null) {
            return defaultValue;
        }
        if (!(rawValue instanceof TomlArray array)) {
            throw new WorkspaceConfigException(
                    "Invalid value for [" + section + "]." + key + " in " + sourceName + ". Use an array of strings.");
        }

        ArrayList<String> values = new ArrayList<>();
        LinkedHashSet<String> uniqueValues = new LinkedHashSet<>();
        for (int index = 0; index < array.size(); index++) {
            String value = array.getString(index);
            if (value == null || value.isBlank()) {
                throw new WorkspaceConfigException(
                        "Invalid value for [" + section + "]." + key + "[" + index + "] in " + sourceName + ". Use a non-empty string.");
            }
            if (!uniqueValues.add(value)) {
                throw new WorkspaceConfigException(
                        "Duplicate value `" + value + "` in [" + section + "]." + key + " in " + sourceName + ".");
            }
            values.add(value);
        }
        return List.copyOf(values);
    }

    private static Map<String, String> stringMap(TomlTable table, String section, String sourceName) {
        if (table == null) {
            return Map.of();
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (String key : table.keySet()) {
            Object rawValue = table.get(List.of(key));
            if (!(rawValue instanceof String value) || value.isBlank()) {
                throw new WorkspaceConfigException(
                        "Invalid value for [" + section + "]." + key + " in " + sourceName + ". Use a non-empty string value.");
            }
            values.put(key, value);
        }
        return values;
    }
}
