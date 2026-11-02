package sh.zolt.build.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunPackageServiceSpringBootWarTest extends RunPackageServiceSpringBootTestSupport {
    @TempDir
    private Path projectDir;

    @Test
    void runsSpringBootWarPackageWithJavaJar() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        writeSpringBootWarLockfile(projectDir, cacheRoot);
        source(projectDir, "src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        List<List<String>> commands = new ArrayList<>();
        RunPackageService service = service((command, outputConsumer) -> {
            commands.add(command);
            return new JavaRunner.ProcessResult(0, "boot war\n");
        });

        RunPackageResult result = service.runPackage(
                projectDir,
                config(java.util.Optional.of("com.example.Main"))
                        .withPackageSettings(new sh.zolt.project.PackageSettings(sh.zolt.project.PackageMode.SPRING_BOOT_WAR)),
                cacheRoot,
                List.of("one", "two"));

        Path warPath = projectDir.resolve("target/demo-0.1.0.war");
        assertEquals(sh.zolt.project.PackageMode.SPRING_BOOT_WAR, result.packageResult().mode());
        assertEquals("boot war\n", result.javaRunResult().output());
        assertTrue(Path.of(commands.getFirst().get(2)).startsWith(projectDir.resolve(".zolt/run")));
        assertTrue(commands.getFirst().get(2).endsWith("demo-0.1.0.war"));
        assertEquals(List.of(
                commands.getFirst().get(0),
                "-jar",
                commands.getFirst().get(2),
                "one",
                "two"), commands.getFirst());
    }
}
