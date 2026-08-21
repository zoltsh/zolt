package sh.zolt.quality;

import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

abstract class QualityCheckServiceTestSupport {
    private static final ManifestProjectConfigLoader MANIFEST_LOADER = new ManifestProjectConfigLoader();

    protected static QualityCheckReport check(
            Path projectDir,
            Path cacheDir,
            Map<String, String> environment,
            QualityCheckContext context,
            List<String> checks) {
        QualityCheckService service = new QualityCheckService(environment::get);
        return service.check(new QualityCheckRequest(
                projectDir,
                cacheDir,
                false,
                false,
                checks,
                context,
                null,
                null,
                false,
                false,
                false,
                WorkspaceSelectionRequest.defaults()));
    }

    protected static ProjectConfig parseProject(Path projectDir, String body) throws IOException {
        Files.createDirectories(projectDir);
        Path config = projectDir.resolve("zolt.toml");
        Files.writeString(config, memberConfig(projectDir.getFileName().toString()) + body);
        return MANIFEST_LOADER.load(config);
    }

    /**
     * Writes {@code memberPaths} as members of a workspace rooted at {@code root} and composes the
     * first one. A {@code workspace = true} selector is only meaningful inside a workspace (design
     * §9.8), so a member fixture that declares one cannot be composed standalone.
     */
    protected static ProjectConfig parseWorkspaceMember(
            Path root,
            String body,
            List<String> memberPaths) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "demo"

                [workspace.members]
                include = [%s]
                """.formatted(memberPaths.stream()
                .map(path -> '"' + path + '"')
                .collect(java.util.stream.Collectors.joining(", "))));
        for (int index = 0; index < memberPaths.size(); index++) {
            Path memberDir = root.resolve(memberPaths.get(index));
            Files.createDirectories(memberDir);
            Files.writeString(
                    memberDir.resolve("zolt.toml"),
                    memberConfig(memberDir.getFileName().toString()) + (index == 0 ? body : ""));
        }
        return new ManifestProjectLoader().load(root.resolve(memberPaths.getFirst()));
    }

    protected static String memberConfig(String name) {
        return """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """.formatted(name);
    }

    protected static String generatedSourceConfig(
            String scope,
            String id,
            String output,
            String input,
            boolean required) {
        return """

                [generated.%s.%s]
                kind = "declared-root"
                language = "java"
                output = "%s"
                inputs = ["%s"]
                required = %s
                """.formatted(scope, id, output, input, required);
    }

    protected static void writeLockfile(Path projectDir, String packages) throws IOException {
        Files.writeString(projectDir.resolve("zolt.lock"), "version = 7\n" + packages);
    }
}
