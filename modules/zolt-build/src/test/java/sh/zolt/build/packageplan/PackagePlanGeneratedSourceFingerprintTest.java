package sh.zolt.build.packageplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.PackageException;
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

    @Test
    void mainOnlyPlanDoesNotProbeUnavailableTestProcessGenerator()
            throws IOException {
        Files.writeString(projectRoot.resolve("zolt.lock"), "version = 1\n");
        Files.writeString(projectRoot.resolve("fixtures.sql"), "seed\n");
        PackagePlanService service = new PackagePlanService();
        Path cacheRoot = projectRoot.resolve("cache");

        PackagePlan mainOnly = service.plan(
                projectRoot,
                unavailableTestProcessConfig(false),
                projectRoot.resolve("zolt.lock"),
                cacheRoot);
        PackageException exception = assertThrows(
                PackageException.class,
                () -> service.plan(
                        projectRoot,
                        unavailableTestProcessConfig(true),
                        projectRoot.resolve("zolt.lock"),
                        cacheRoot));

        assertEquals(
                "sha256:",
                mainOnly.evidence()
                        .buildInputFingerprint()
                        .substring(0, 7));
        assertTrue(exception.getMessage().contains(
                "[generated.test.fixtures] could not find process binary "
                        + "`zolt-missing-test-generator`"));
    }

    @Test
    void testGeneratorChangesAffectOnlyPlansThatIncludeTests()
            throws IOException {
        Files.writeString(projectRoot.resolve("zolt.lock"), "version = 1\n");
        PackagePlanService service = new PackagePlanService();
        Path cacheRoot = projectRoot.resolve("cache");

        PackagePlan mainV1 = service.plan(
                projectRoot,
                declaredTestConfig(false, "fixtures-v1.sql"),
                projectRoot.resolve("zolt.lock"),
                cacheRoot);
        PackagePlan mainV2 = service.plan(
                projectRoot,
                declaredTestConfig(false, "fixtures-v2.sql"),
                projectRoot.resolve("zolt.lock"),
                cacheRoot);
        PackagePlan testsV1 = service.plan(
                projectRoot,
                declaredTestConfig(true, "fixtures-v1.sql"),
                projectRoot.resolve("zolt.lock"),
                cacheRoot);
        PackagePlan testsV2 = service.plan(
                projectRoot,
                declaredTestConfig(true, "fixtures-v2.sql"),
                projectRoot.resolve("zolt.lock"),
                cacheRoot);

        assertEquals(
                mainV1.evidence().buildInputFingerprint(),
                mainV2.evidence().buildInputFingerprint());
        assertEquals(
                mainV1.evidence().inputFingerprint(),
                mainV2.evidence().inputFingerprint());
        assertEquals(
                testsV1.evidence().buildInputFingerprint(),
                testsV2.evidence().buildInputFingerprint());
        assertNotEquals(
                testsV1.evidence().inputFingerprint(),
                testsV2.evidence().inputFingerprint());
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

    private static ProjectConfig unavailableTestProcessConfig(
            boolean packageTests) {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [generated.execTools.test-generator]
                runner = "process"
                binary = "zolt-missing-test-generator"
                versionCommand = ["zolt-missing-test-generator", "--version"]
                allowUnpinnedTool = true

                [generated.test.fixtures]
                kind = "exec"
                tool = "test-generator"
                inputs = ["fixtures.sql"]
                output = "target/generated/test-fixtures"
                produces = "test-resources"

                [package]
                tests = %s
                """.formatted(packageTests));
    }

    private static ProjectConfig declaredTestConfig(
            boolean packageTests,
            String input) {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [generated.test.fixtures]
                kind = "declared-root"
                language = "java"
                inputs = ["%s"]
                output = "target/generated/test-fixtures"

                [package]
                tests = %s
                """.formatted(input, packageTests));
    }
}
