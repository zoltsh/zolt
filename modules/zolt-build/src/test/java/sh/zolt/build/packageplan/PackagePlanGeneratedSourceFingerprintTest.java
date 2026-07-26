package sh.zolt.build.packageplan;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackagePlanGeneratedSourceFingerprintTest {
    @TempDir
    private Path projectRoot;

    @Test
    void packageBuildFingerprintUsesCurrentGlobAndJvmToolBytes()
            throws IOException {
        Path cacheRoot = projectRoot.resolve("custom-cache");
        Path toolJar = cacheRoot.resolve(
                "org/jooq/jooq-codegen/3.19.15/jooq-codegen-3.19.15.jar");
        Files.createDirectories(toolJar.getParent());
        Files.writeString(toolJar, "tool-v1\n");
        Files.createDirectories(projectRoot.resolve("schemas/api"));
        Files.writeString(projectRoot.resolve("zolt.lock"), """
                version = 1

                [[package]]
                id = "org.jooq:jooq-codegen"
                version = "3.19.15"
                source = "maven-central"
                scope = "tool-exec"
                direct = true
                jar = "org/jooq/jooq-codegen/3.19.15/jooq-codegen-3.19.15.jar"
                dependencies = []
                toolGroups = ["jooq"]
                """);
        PackagePlanService service = new PackagePlanService();
        ProjectConfig config = config();

        String initial = buildFingerprint(service, config, cacheRoot);
        Files.writeString(
                projectRoot.resolve("schemas/api/model.json"),
                "{\"v\":1}\n");
        String globAdded = buildFingerprint(service, config, cacheRoot);
        Files.writeString(toolJar, "tool-v2\n");
        String toolChanged = buildFingerprint(service, config, cacheRoot);

        assertNotEquals(initial, globAdded);
        assertNotEquals(globAdded, toolChanged);
    }

    private String buildFingerprint(
            PackagePlanService service,
            ProjectConfig config,
            Path cacheRoot) {
        return service.plan(
                        projectRoot,
                        config,
                        projectRoot.resolve("zolt.lock"),
                        cacheRoot)
                .evidence()
                .buildInputFingerprint();
    }

    private static ProjectConfig config() {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [generated.execTools.jooq]
                runner = "jvm"
                coordinates = [{ coordinate = "org.jooq:jooq-codegen", version = "3.19.15" }]
                mainClass = "com.example.GenerationTool"

                [generated.main.model]
                kind = "exec"
                tool = "jooq"
                inputs = ["schemas/**/*.json"]
                output = "target/generated/sources/model"
                produces = "java-sources"
                """);
    }
}
