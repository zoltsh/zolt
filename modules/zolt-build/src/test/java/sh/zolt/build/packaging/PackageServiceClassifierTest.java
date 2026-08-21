package sh.zolt.build.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanDependency;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageServiceClassifierTest {
    @TempDir
    private Path tempDir;

    @Test
    void thinPackagePlanShowsClassifierRuntimeArtifact() throws IOException {
        Path projectDir = project("thin");

        PackagePlan plan = new PackagePlanService().plan(projectDir, config("jar"));

        PackagePlanDependency nativeLib = plan.dependencies().getFirst();
        assertEquals("com.example:native-lib:linux-x86_64:jar:1.0.0", nativeLib.coordinate());
        assertEquals("thin-runtime-classpath", nativeLib.ruleName());
        assertEquals("runtime-classpath sidecar", nativeLib.location());
    }

    @Test
    void uberPackagePlanShowsClassifierRuntimeArtifactPlacement() throws IOException {
        Path projectDir = project("uber");

        PackagePlan plan = new PackagePlanService().plan(projectDir, config("uber-jar"));

        PackagePlanDependency nativeLib = plan.dependencies().getFirst();
        assertEquals("com.example:native-lib:linux-x86_64:jar:1.0.0", nativeLib.coordinate());
        assertEquals("uber-runtime-merged", nativeLib.ruleName());
        assertEquals("archive root", nativeLib.location());
    }

    private Path project(String mode) throws IOException {
        Path projectDir = tempDir.resolve(mode);
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 7

                [[package]]
                id = "com.example:native-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "runtime"
                direct = false
                jar = "com/example/native-lib/1.0.0/native-lib-1.0.0-linux-x86_64.jar"
                dependencies = []
                """);
        return projectDir;
    }

    private static ProjectConfig config(String mode) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21
                main = "com.example.Main"

                [package]
                mode = "%s"
                """.formatted(mode));
    }
}
