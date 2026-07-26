package sh.zolt.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;

final class DependencyPolicyReportArtifactIdentityTest {
    private static final PackageId LIB = new PackageId("com.example", "lib");

    @TempDir
    private Path tempDir;

    @Test
    void directVersionRequiresTheDeclaredVariantAndScope() throws IOException {
        ProjectConfig config = config();
        ZoltLockfile wrongIdentities = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(
                        lockPackage(DependencyScope.RUNTIME, null),
                        lockPackage(DependencyScope.COMPILE, "linux")),
                List.of());

        DependencyPolicyReport wrong =
                new DependencyPolicyReportService().report(tempDir, config, wrongIdentities);
        assertEquals("not-selected", wrong.directVersions().getFirst().status());

        ZoltLockfile exact = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(lockPackage(DependencyScope.RUNTIME, "linux")),
                List.of());
        DependencyPolicyReport selected =
                new DependencyPolicyReportService().report(tempDir, config, exact);
        assertEquals("selected", selected.directVersions().getFirst().status());
    }

    private ProjectConfig config() throws IOException {
        Path config = tempDir.resolve("zolt.toml");
        Files.writeString(config, """
                [project]
                name = "demo"
                version = "1.0.0"
                group = "com.example"
                java = "21"

                [runtime.dependencies]
                "com.example:lib" = { version = "2.0.0", classifier = "linux" }
                """);
        return new ZoltTomlParser().parse(config);
    }

    private static LockPackage lockPackage(
            DependencyScope scope,
            String classifier) {
        String suffix = classifier == null ? "" : "-" + classifier;
        String base = "com/example/lib/2.0.0/lib-2.0.0";
        return new LockPackage(
                LIB,
                "2.0.0",
                "central",
                scope,
                true,
                Optional.of(base + suffix + ".jar"),
                Optional.of(base + ".pom"),
                Optional.of("jar-sha" + suffix),
                Optional.of("pom-sha"),
                List.of());
    }
}
