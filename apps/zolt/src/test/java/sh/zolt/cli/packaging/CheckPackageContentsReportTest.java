package sh.zolt.cli.packaging;

import static sh.zolt.cli.packaging.CheckPackageContentsCommandTestSupport.writePackagePlanLockfile;
import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static sh.zolt.cli.packaging.PackageSpringBootCommandTestSupport.nestedJarName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CheckPackageContentsReportTest {
    @TempDir
    private Path tempDir;

    @Test
    void checkPackageContentsReportsSuspiciousWarContainerDependency() throws IOException {
        Path projectDir = tempDir.resolve("check-package-contents-war");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-package-contents-war") + """

                [package]
                mode = "war"
                """);
        writePackagePlanLockfile(projectDir, false, true);

        CommandResult result = execute(
                "check",
                "--cwd", projectDir.toString(),
                "--check", "package-contents");

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error package-contents org.apache.tomcat.embed:tomcat-embed-core:10.1.40"));
        assertTrue(result.stdout().contains(
                "Container-style dependency `org.apache.tomcat.embed:tomcat-embed-core:10.1.40` is packaged in WEB-INF/lib/"
                        + nestedJarName(
                                "org.apache.tomcat.embed",
                                "tomcat-embed-core",
                                "10.1.40")
                        + " by package rule `war-runtime-lib`."));
        assertTrue(result.stdout().contains("next: Move it to [dependencies.provided]"));
    }

    @Test
    void checkPackageContentsReportsPolicyEffects() throws IOException {
        Path projectDir = tempDir.resolve("check-package-contents-policy");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-package-contents-policy") + """

                [package]
                mode = "spring-boot"
                """);
        writePackagePlanLockfile(projectDir, true, false);

        CommandResult result = execute(
                "check",
                "--cwd", projectDir.toString(),
                "--check", "package-contents");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok package-contents check-package-contents-policy Package mode `spring-boot` has"));
        assertTrue(result.stdout().contains("1 dependencies include dependency policy effects."));
    }

    @Test
    void checkPackageContentsReportsPackageRuleDiagnostics() throws IOException {
        Path projectDir = tempDir.resolve("check-package-contents-rules");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-package-contents-rules") + """

                [package]
                mode = "spring-boot-war"
                """);
        writePackagePlanLockfile(projectDir, true, false);

        CommandResult result = execute(
                "check",
                "--cwd", projectDir.toString(),
                "--check", "package-contents");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok package-contents check-package-contents-rules Package mode `spring-boot-war` has"));
        assertTrue(result.stdout().contains("ok package-contents rule:spring-boot-war-provided-lib 1 dependency uses package rule `spring-boot-war-provided-lib` with scope `provided`, disposition `provided`, and location `WEB-INF/lib-provided/*`."));
        assertTrue(result.stdout().contains("ok package-contents rule:dev-only-omitted 1 dependency uses package rule `dev-only-omitted` with scope `dev`, disposition `omitted`, and location `none`."));
        assertTrue(result.stdout().contains("ok package-contents rule:processor-omitted 1 dependency uses package rule `processor-omitted` with scope `processor`, disposition `omitted`, and location `none`."));
        assertTrue(result.stdout().contains("ok package-contents rule:test-omitted 1 dependency uses package rule `test-omitted` with scope `test`, disposition `omitted`, and location `none`."));
        assertTrue(result.stdout().contains("ok package-contents rule:spring-boot-war-runtime-lib"));
        assertTrue(result.stdout().contains("1 includes dependency policy effects."));
    }
}
