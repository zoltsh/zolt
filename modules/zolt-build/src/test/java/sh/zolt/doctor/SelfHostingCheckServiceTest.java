package sh.zolt.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SelfHostingCheckServiceTest {
    private static final String JUNIT_CONSOLE = "org.junit.platform:junit-platform-console-standalone";

    @TempDir
    private Path projectDir;

    @Test
    void checksSelfHostingProjectFromZoltToml() throws IOException {
        write("zolt.toml", """
                [project]
                name = "zoltish"
                version = "0.1.0"
                group = "sh.zolt"
                java = "21"
                main = "sh.zolt.Main"

                [test.sources]
                java = []
                groovy = ["src/test/groovy"]

                [test.dependencies]
                "org.junit.platform:junit-platform-console-standalone" = "1.11.4"

                [native]
                args = ["--no-fallback"]
                """);
        write("zolt.lock", "version = 7\n");
        Files.createDirectories(projectDir.resolve("src/main/java"));
        Files.createDirectories(projectDir.resolve("src/test/groovy"));

        SelfHostingCheckResult result = new SelfHostingCheckService().check(projectDir);

        assertTrue(result.ok());
        assertEquals(
                java.util.List.of(
                        "main class",
                        "lockfile",
                        "main sources",
                        "test sources",
                        "JUnit Platform Console",
                        "native image name",
                        "native output",
                        "native no-fallback"),
                result.checks().stream().map(SelfHostingCheckResult.SelfHostingCheck::name).toList());
        assertEquals("project main is sh.zolt.Main", byName(result).get("main class").message());
        assertEquals("native image name is zoltish", byName(result).get("native image name").message());
    }

    @Test
    void reportsActionableFailuresAndCountsManagedConsoleDependency() throws IOException {
        write("zolt.toml", """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [test.dependencies]
                "org.junit.platform:junit-platform-console-standalone" = {}
                """);

        SelfHostingCheckResult result = new SelfHostingCheckService().check(projectDir);
        Map<String, SelfHostingCheckResult.SelfHostingCheck> checks = byName(result);

        assertFalse(result.ok());
        assertEquals("add [project].main so Zolt can run and package itself", checks.get("main class").message());
        assertEquals("run zolt resolve to create zolt.lock", checks.get("lockfile").message());
        assertEquals(
                "create a configured main source root or update [build].sources",
                checks.get("main sources").message());
        assertEquals(
                "create a configured test source root or update [test.sources]",
                checks.get("test sources").message());
        assertTrue(checks.get("JUnit Platform Console").ok());
        assertEquals(JUNIT_CONSOLE + " is declared", checks.get("JUnit Platform Console").message());
        assertEquals(
                "add --no-fallback to [native].args for release-grade self-hosting",
                checks.get("native no-fallback").message());
    }

    private void write(String relativePath, String content) throws IOException {
        Path path = projectDir.resolve(relativePath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, content);
    }

    private static Map<String, SelfHostingCheckResult.SelfHostingCheck> byName(SelfHostingCheckResult result) {
        return result.checks().stream().collect(Collectors.toMap(
                SelfHostingCheckResult.SelfHostingCheck::name,
                Function.identity()));
    }
}
