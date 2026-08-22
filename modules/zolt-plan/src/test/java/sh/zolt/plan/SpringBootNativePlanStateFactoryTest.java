package sh.zolt.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.FrameworkSettings;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.QuarkusSettings;
import sh.zolt.project.SpringBootSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SpringBootNativePlanStateFactoryTest {
    private final SpringBootNativePlanStateFactory factory = new SpringBootNativePlanStateFactory();

    @TempDir
    private Path projectDir;

    @Test
    void stateSortsAotEvidenceAndResolvesRelativeNativeImageExecutable() throws IOException {
        FileTime inputTime = FileTime.fromMillis(1_000);
        FileTime aotTime = FileTime.fromMillis(2_000);
        Path nativeImage = projectDir.resolve("tools/native-image");
        write(nativeImage, "#!/bin/sh\nexit 0\n", aotTime);
        assertTrue(nativeImage.toFile().setExecutable(true));
        write(projectDir.resolve("zolt.toml"), "[project]\nname = \"demo\"\n", inputTime);
        write(projectDir.resolve("target/classes/com/example/DemoApplication.class"), "class-bytes", inputTime);
        write(projectDir.resolve("target/spring-aot/main/sources/com/example/ZBean.java"), "final class ZBean {}\n", aotTime);
        write(projectDir.resolve("target/spring-aot/main/sources/com/example/ABean.java"), "final class ABean {}\n", aotTime);
        write(projectDir.resolve("target/spring-aot/main/classes/com/example/ZBean.class"), "class-bytes", aotTime);
        write(projectDir.resolve("target/spring-aot/main/classes/com/example/ABean.class"), "class-bytes", aotTime);
        write(projectDir.resolve("target/spring-aot/main/resources/META-INF/native-image/z/reflect-config.json"), "[]\n", aotTime);
        write(projectDir.resolve("target/spring-aot/main/resources/META-INF/native-image/a/reflect-config.json"), "[]\n", aotTime);
        write(projectDir.resolve("target/spring-aot/main/resources/META-INF/native-image/reachability-metadata.json"), "{}\n", aotTime);

        SpringBootNativePlanState state = factory.state(
                projectDir.resolve(".").resolve("..").resolve(projectDir.getFileName()),
                projectDir.resolve("zolt.lock"),
                nativeConfig(new NativeSettings("", ".zolt/native", List.of("--verbose"))),
                Optional.of(Path.of("tools/native-image")));

        assertEquals(projectDir.toAbsolutePath().normalize(), state.projectRoot());
        assertEquals(Path.of("tools/native-image"), state.nativeImageExecutable());
        assertTrue(state.nativeImageAvailable());
        assertEquals(projectDir.resolve("target/demo-1.0.0.jar").normalize(), state.packageJar());
        assertEquals(projectDir.resolve(".zolt/native/demo").normalize(), state.image());
        assertEquals(
                List.of(
                        projectDir.resolve("target/spring-aot/main/sources/com/example/ABean.java").normalize(),
                        projectDir.resolve("target/spring-aot/main/sources/com/example/ZBean.java").normalize()),
                state.generatedSources());
        assertEquals(
                List.of(
                        projectDir.resolve("target/spring-aot/main/classes/com/example/ABean.class").normalize(),
                        projectDir.resolve("target/spring-aot/main/classes/com/example/ZBean.class").normalize()),
                state.generatedClasses());
        assertEquals(
                List.of(
                        projectDir.resolve("target/spring-aot/main/resources/META-INF/native-image/a/reflect-config.json").normalize(),
                        projectDir.resolve("target/spring-aot/main/resources/META-INF/native-image/z/reflect-config.json").normalize()),
                state.reflectionMetadata());
        assertEquals("present", state.aotFreshness().label());
        assertFalse(state.aotFreshness().stale());
    }

    @Test
    void stateCapturesInvalidLockfileReadErrorWithoutThrowing() throws IOException {
        write(projectDir.resolve("zolt.lock"), "version = [\n", FileTime.fromMillis(1_000));

        SpringBootNativePlanState state = factory.state(
                projectDir,
                projectDir.resolve("zolt.lock"),
                nativeConfig(NativeSettings.defaults()),
                Optional.of(projectDir.resolve("missing/native-image")));

        assertTrue(state.lockfile().isEmpty());
        assertTrue(state.lockfileError().isPresent());
        assertFalse(state.lockfileError().orElseThrow().isBlank());
    }

    @Test
    void stateReportsMissingAotFreshnessWhenNoAotEvidenceExists() {
        SpringBootNativePlanState state = factory.state(
                projectDir,
                projectDir.resolve("zolt.lock"),
                nativeConfig(NativeSettings.defaults()),
                Optional.empty());

        assertEquals("missing", state.aotFreshness().label());
        assertFalse(state.aotFreshness().stale());
        assertTrue(state.generatedSources().isEmpty());
        assertTrue(state.generatedClasses().isEmpty());
        assertTrue(state.reflectionMetadata().isEmpty());
        assertTrue(state.reachabilityMetadata().isEmpty());
    }

    private static ProjectConfig nativeConfig(NativeSettings nativeSettings) {
        return ProjectConfigs.withDirectDependencies(
                        new ProjectMetadata(
                                "demo",
                                "1.0.0",
                                "com.example",
                                "21",
                                Optional.of("com.example.DemoApplication")),
                        Map.of(),
                        Map.of("org.springframework.boot:spring-boot-starter-web", "3.3.6"),
                        Map.of(),
                        BuildSettings.defaults(),
                        nativeSettings)
                .withFrameworkSettings(new FrameworkSettings(new SpringBootSettings(true), QuarkusSettings.defaults()))
                .withPackageSettings(new PackageSettings(PackageMode.SPRING_BOOT));
    }

    private static void write(Path path, String content, FileTime time) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, content);
        Files.setLastModifiedTime(path, time);
    }
}
