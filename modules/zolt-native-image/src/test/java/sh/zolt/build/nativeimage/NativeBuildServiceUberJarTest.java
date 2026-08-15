package sh.zolt.build.nativeimage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.packaging.PackageResult;
import sh.zolt.build.packaging.PackageService;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

final class NativeBuildServiceUberJarTest extends NativeBuildServiceTestSupport {
    @Test
    void preservesConfiguredUberJarWhenBuildingNativeImage() throws IOException {
        Path cacheRoot = projectDir.resolve("cache");
        writeRuntimeLockfile();
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        ProjectConfig config = config(Optional.of("com.example.Main"))
                .withPackageSettings(new PackageSettings(PackageMode.UBER));
        PackageResult packaged = new PackageService().packageJar(projectDir, config, cacheRoot);
        byte[] packagedBytes = Files.readAllBytes(packaged.jarPath());
        List<List<String>> commands = new ArrayList<>();
        NativeBuildService service = service(command -> {
            commands.add(command);
            writeNativeBinary(Path.of(command.getLast()));
            return new NativeImageRunner.ProcessResult(0, "native ok\n");
        });

        NativeBuildResult result = service.buildNative(
                projectDir,
                config,
                cacheRoot,
                Path.of("custom-native-image"));

        Path publicJarPath = projectDir.resolve("target/demo-0.1.0.jar");
        Path nativeInputJar = projectDir.resolve("target/native-custom/input/demo-0.1.0.jar");
        Path outputBinary = projectDir.resolve("target/native-custom/demo-native");
        Path logFile = projectDir.resolve("target/native-custom/native-image.log");
        assertEquals(nativeInputJar, result.packageResult().jarPath());
        assertEquals(PackageMode.UBER, result.packageResult().mode());
        assertTrue(result.packageResult().runtimeClasspathPath().isEmpty());
        assertEquals(outputBinary, result.nativeImageResult().outputBinary());
        assertEquals(logFile, result.nativeImageResult().logFile());
        assertTrue(Files.exists(publicJarPath));
        assertArrayEquals(packagedBytes, Files.readAllBytes(publicJarPath));
        try (JarFile jar = new JarFile(nativeInputJar.toFile())) {
            assertNotNull(jar.getJarEntry("com/example/Main.class"));
            assertNotNull(jar.getJarEntry("com/example/runtime/RuntimeLib.class"));
        }
        assertTrue(Files.exists(outputBinary));
        assertEquals("native ok\n", Files.readString(logFile));
        assertEquals(List.of(
                "custom-native-image",
                "-J-Dzolt.build.version=0.1.0",
                "--no-fallback",
                "--native-image-info",
                "-cp",
                nativeInputJar.toString(),
                "com.example.Main",
                "-o",
                outputBinary.toString()), commands.getFirst());
    }
}
