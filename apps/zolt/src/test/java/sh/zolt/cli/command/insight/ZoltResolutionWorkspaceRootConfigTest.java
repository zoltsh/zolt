package sh.zolt.cli.command.insight;

import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.command.insight.ZoltResolutionLoader.ZoltResolution;
import sh.zolt.dependency.PackageId;
import sh.zolt.explain.verify.VerifyScope;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.resolve.ResolveService;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code zolt explain verify} must resolve each workspace member with the workspace-root
 * {@code [repositories]} and {@code [platforms]} merged in — exactly what {@code zolt resolve
 * --workspace} applies. Here a member declares a platform-managed dependency with no member-level
 * repository or platform of its own, so it resolves only when root config is folded in. The assertion
 * compares the loader's resolved version against what the workspace resolver actually locks, not a
 * hardcoded value.
 */
final class ZoltResolutionWorkspaceRootConfigTest {
    @TempDir
    private Path tempDir;

    @Test
    void memberResolutionMatchesWorkspaceResolveForRootRepositoriesAndPlatforms() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            repository.addArtifact("com.example", "bom", "1.0.0", """
                    <project>
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>com.example</groupId>
                      <artifactId>bom</artifactId>
                      <version>1.0.0</version>
                      <dependencyManagement>
                        <dependencies>
                          <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>lib</artifactId>
                            <version>1.0.0</version>
                          </dependency>
                        </dependencies>
                      </dependencyManagement>
                    </project>
                    """);
            repository.addArtifact("com.example", "lib", "1.0.0", pom("com.example", "lib", "1.0.0"));
            Path workspaceDir = tempDir.resolve("ws");
            writeWorkspace(workspaceDir, repository.baseUri().toString());
            Path cache = tempDir.resolve("cache");

            // Source of truth: what `zolt resolve --workspace` locks for the managed dependency.
            ResolveResult workspaceResult =
                    new WorkspaceResolveService().resolve(workspaceDir, cache, false, false);
            String expected = lockedVersion(workspaceResult.lockfilePath(), "com.example", "lib");

            // Verify's loader must reach the same version by merging root [platforms] + [repositories].
            ZoltResolution zolt = new ZoltResolutionLoader(new ResolveService())
                    .load(workspaceDir, cache, false, List.of());

            List<String> compileCoordinates = zolt.modules().stream()
                    .flatMap(module -> module.artifacts(VerifyScope.COMPILE).stream())
                    .map(artifact -> artifact.coordinate())
                    .toList();
            assertTrue(compileCoordinates.contains("com.example:lib:" + expected),
                    "expected com.example:lib:" + expected + " but resolved " + compileCoordinates);
        }
    }

    private static String lockedVersion(Path lockfile, String group, String artifact) throws IOException {
        ZoltLockfile parsed = new ZoltLockfileReader().read(lockfile);
        return parsed.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId(group, artifact)))
                .map(lockPackage -> lockPackage.version())
                .findFirst()
                .orElseThrow();
    }

    private static void writeWorkspace(Path workspaceDir, String repositoryUrl) throws IOException {
        Files.createDirectories(workspaceDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "demo"

                [workspace.members]
                include = ["app"]

                [repositories.test]
                url = "%s"

                [platforms]
                "com.example:bom" = "1.0.0"
                """.formatted(repositoryUrl));
        Path member = workspaceDir.resolve("app");
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), memberConfig("app") + """

                [dependencies]
                "com.example:lib" = {}
                """);
    }

    private static String pom(String group, String artifact, String version) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(group, artifact, version);
    }
}
