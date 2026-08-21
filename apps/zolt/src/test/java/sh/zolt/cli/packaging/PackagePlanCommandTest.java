package sh.zolt.cli.packaging;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static sh.zolt.cli.ContentAddressedLockTestSupport.write;
import static sh.zolt.cli.packaging.PackageSpringBootCommandTestSupport.nestedJarName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackagePlanCommandTest extends PackagePlanCommandTestSupport {
    @TempDir
    private Path tempDir;

    @Test
    void packagePlanPrintsSpringBootWarDependencyDispositions() throws IOException {
        Path projectDir = tempDir.resolve("package-plan-boot-war");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("package-plan-boot-war") + """

                [package]
                mode = "spring-boot-war"
                """);
        writePackagePlanLockfile(projectDir, false, true);

        CommandResult result = execute(
                "package",
                "--plan",
                "--cwd", projectDir.toString(),
                "--cache-root", projectDir.resolve(".zolt/cache").toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Package plan"));
        assertTrue(result.stdout().contains("Mode: spring-boot-war"));
        assertTrue(result.stdout().contains("Application layout: WEB-INF/classes"));
        String runtimeNestedName =
                nestedJarName("com.example", "runtime-lib", "1.0.0");
        String providedNestedName =
                nestedJarName(
                        "jakarta.servlet",
                        "jakarta.servlet-api",
                        "6.1.0");
        assertTrue(result.stdout().contains("org.springframework.boot:spring-boot-loader:4.0.6 [runtime] loader -> archive root rule=spring-boot-war-loader-expanded"));
        assertTrue(result.stdout().contains("com.example:runtime-lib:1.0.0 [runtime] included -> WEB-INF/lib/" + runtimeNestedName + " rule=spring-boot-war-runtime-lib"));
        assertTrue(result.stdout().contains("jakarta.servlet:jakarta.servlet-api:6.1.0 [provided] provided -> WEB-INF/lib-provided/" + providedNestedName + " rule=spring-boot-war-provided-lib"));
        assertTrue(result.stdout().contains("com.example:devtools:1.0.0 [dev] omitted rule=dev-only-omitted"));
        assertTrue(result.stdout().contains("jakarta.servlet:jakarta.servlet-api:6.1.0 [provided] provided -> WEB-INF/lib-provided/" + providedNestedName + " rule=spring-boot-war-provided-lib lanes=compile packageDefault=false lane=provided-container"));
        assertTrue(result.stdout().contains("com.example:devtools:1.0.0 [dev] omitted rule=dev-only-omitted lanes=runtime,test packageDefault=false lane=development-only"));
        assertTrue(result.stdout().contains("warning CONTAINER_DEPENDENCY_PACKAGED org.apache.tomcat.embed:tomcat-embed-core:10.1.40 rule=spring-boot-war-runtime-lib"));
        assertFalse(Files.exists(projectDir.resolve("target/package-plan-boot-war-0.1.0.war")));
    }

    @Test
    void packagePlanPrintsQuarkusDependencyDispositions() throws IOException {
        Path projectDir = tempDir.resolve("package-plan-quarkus");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("package-plan-quarkus") + """

                [build.output]
                root = ".zolt/build"

                [package]
                mode = "quarkus"
                """);
        write(projectDir.resolve("zolt.lock"), projectDir.resolve(".zolt/cache"), """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "io.quarkus:quarkus-rest"
                version = "3.33.0"
                lane = "runtime"
                resolvedScope = "runtime"

                [[package]]
                id = "io.quarkus:quarkus-rest"
                version = "3.33.0"
                source = "maven-central"
                scope = "runtime"
                direct = true
                jar = "io/quarkus/quarkus-rest/3.33.0/quarkus-rest-3.33.0.jar"
                dependencies = []

                [[package]]
                id = "io.quarkus:quarkus-rest-deployment"
                version = "3.33.0"
                source = "maven-central"
                scope = "quarkus-deployment"
                direct = false
                jar = "io/quarkus/quarkus-rest-deployment/3.33.0/quarkus-rest-deployment-3.33.0.jar"
                dependencies = []
                """);

        CommandResult result = execute(
                "package",
                "--plan",
                "--cwd", projectDir.toString(),
                "--cache-root", projectDir.resolve(".zolt/cache").toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Mode: quarkus"));
        assertTrue(result.stdout().contains(
                "Archive: " + projectDir.resolve(".zolt/build/quarkus-app/quarkus-run.jar")));
        assertTrue(result.stdout().contains("Application layout: .zolt/build/quarkus-app/app"));
        assertTrue(result.stdout().contains(
                "io.quarkus:quarkus-rest:3.33.0 [runtime] included -> .zolt/build/quarkus-app/lib/quarkus-rest-3.33.0.jar rule=quarkus-runtime-lib"));
        assertTrue(result.stdout().contains(
                "io.quarkus:quarkus-rest-deployment:3.33.0 [quarkus-deployment] omitted rule=quarkus-deployment-omitted"));
        assertFalse(Files.exists(projectDir.resolve("target/quarkus-app/quarkus-run.jar")));
    }

    @Test
    void packagePlanPrintsUberDependencyDispositions() throws IOException {
        Path projectDir = tempDir.resolve("package-plan-uber");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("package-plan-uber") + """

                [package]
                mode = "uber-jar"
                """);
        writePackagePlanLockfile(projectDir, false, false);

        CommandResult result = execute(
                "package",
                "--plan",
                "--cwd", projectDir.toString(),
                "--cache-root", projectDir.resolve(".zolt/cache").toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Mode: uber"));
        assertTrue(result.stdout().contains("Application layout: archive root"));
        assertTrue(result.stdout().contains(
                "com.example:runtime-lib:1.0.0 [runtime] included -> archive root rule=uber-runtime-merged"));
        assertTrue(result.stdout().contains("jakarta.servlet:jakarta.servlet-api:6.1.0 [provided] omitted rule=provided-container-omitted"));
        assertTrue(result.stdout().contains("com.example:processor:1.0.0 [processor] omitted rule=processor-omitted"));
        assertFalse(Files.exists(projectDir.resolve("target/package-plan-uber-0.1.0.jar")));
    }

    @Test
    void packagePlanUsesOutputRootForArchiveAndRuntimeClasspath() throws IOException {
        Path projectDir = tempDir.resolve("package-plan-output-root");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("package-plan-output-root") + """

                [build.output]
                root = ".zolt/build"

                [package]
                mode = "jar"
                """);
        writePackagePlanLockfile(projectDir, false, false);

        CommandResult result = execute(
                "package",
                "--plan",
                "--cwd", projectDir.toString(),
                "--cache-root", projectDir.resolve(".zolt/cache").toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains(
                "Archive: " + projectDir.resolve(".zolt/build/package-plan-output-root-0.1.0.jar")));
        assertTrue(result.stdout().contains(
                "Runtime classpath sidecar: " + projectDir.resolve(".zolt/build/package-plan-output-root-0.1.0.runtime-classpath")));
        assertFalse(Files.exists(projectDir.resolve("target/package-plan-output-root-0.1.0.jar")));
    }
}
