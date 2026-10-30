package sh.zolt.build.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunPackageServiceSpringBootTest extends RunPackageServiceSpringBootTestSupport {
    @TempDir
    private Path projectDir;

    @Test
    void runsSpringBootPackageWithJavaJar() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        writeSpringBootLockfile(projectDir, cacheRoot);
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
            return new JavaRunner.ProcessResult(0, "boot\n");
        });

        RunPackageResult result = service.runPackage(
                projectDir,
                config(java.util.Optional.of("com.example.Main"))
                        .withPackageSettings(new sh.zolt.project.PackageSettings(sh.zolt.project.PackageMode.SPRING_BOOT)),
                cacheRoot,
                List.of("one", "two"));

        Path jarPath = projectDir.resolve("target/demo-0.1.0.jar");
        assertEquals(sh.zolt.project.PackageMode.SPRING_BOOT, result.packageResult().mode());
        assertEquals("boot\n", result.javaRunResult().output());
        assertTrue(Path.of(commands.getFirst().get(2)).startsWith(projectDir.resolve(".zolt/run")));
        assertTrue(commands.getFirst().get(2).endsWith("demo-0.1.0.jar"));
        assertEquals(List.of(
                commands.getFirst().get(0),
                "-jar",
                commands.getFirst().get(2),
                "one",
                "two"), commands.getFirst());
    }
}
