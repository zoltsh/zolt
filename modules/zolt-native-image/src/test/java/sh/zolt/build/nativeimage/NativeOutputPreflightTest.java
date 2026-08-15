package sh.zolt.build.nativeimage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.NativeImageException;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltTomlParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class NativeOutputPreflightTest {
    @TempDir
    private Path project;

    @Test
    void rejectsSourceReplacementBeforePackagingOrProcessExecution() throws IOException {
        Path source = project.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "authoritative source\n");
        byte[] original = Files.readAllBytes(source);
        List<List<String>> commands = new ArrayList<>();
        NativeBuildService service = service(commands);

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> service.buildNative(
                        project,
                        config("src/main/java/com/example", "Main.java", ""),
                        project.resolve("cache"),
                        Path.of("native-image")));

        assertTrue(exception.getMessage().contains("main source root"), exception.getMessage());
        assertArrayEquals(original, Files.readAllBytes(source));
        assertTrue(commands.isEmpty());
        assertFalse(Files.exists(project.resolve("target/demo-0.1.0.jar")));
    }

    @Test
    void rejectsCompiledClassReplacementBeforePackaging() throws IOException {
        Path compiledClass = project.resolve("target/classes/com/example/Main.class");
        Files.createDirectories(compiledClass.getParent());
        Files.writeString(compiledClass, "authoritative class");
        byte[] original = Files.readAllBytes(compiledClass);
        List<List<String>> commands = new ArrayList<>();

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> service(commands).buildNative(
                        project,
                        config("target/classes/com/example", "Main.class", ""),
                        project.resolve("cache"),
                        Path.of("native-image")));

        assertTrue(exception.getMessage().contains("compiled main classes"), exception.getMessage());
        assertArrayEquals(original, Files.readAllBytes(compiledClass));
        assertTrue(commands.isEmpty());
    }

    @Test
    void rejectsConfiguredNativeImageExecutableOwnership() throws IOException {
        Path executable = project.resolve("tools/native-image");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "#!/bin/sh\nexit 0\n");
        executable.toFile().setExecutable(true, false);
        byte[] original = Files.readAllBytes(executable);
        List<List<String>> commands = new ArrayList<>();

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> service(commands).buildNative(
                        project,
                        config("tools", "native-image", ""),
                        project.resolve("cache"),
                        Path.of("tools/native-image")));

        assertTrue(exception.getMessage().contains("configured native-image executable"), exception.getMessage());
        assertArrayEquals(original, Files.readAllBytes(executable));
        assertTrue(commands.isEmpty());
    }

    @Test
    void rejectsCacheManifestResourceAndGeneratedOutputNamespaces() throws IOException {
        Files.writeString(project.resolve("zolt.toml"), "project manifest\n");
        for (Collision collision : List.of(
                new Collision("cache/native", "demo", project.resolve("cache"), "artifact cache root"),
                new Collision("zolt.toml", "demo", null, "project manifest"),
                new Collision("zolt.lock", "demo", null, "project lockfile"),
                new Collision("zolt-workspace.toml", "demo", null, "workspace manifest"),
                new Collision("src/main/resources", "demo", null, "main resource root"),
                new Collision("src/test/java", "demo", null, "test source root"),
                new Collision("src/integration-test/java", "demo", null, "integration-test source root"),
                new Collision("target/test-classes", "demo", null, "compiled test classes"),
                new Collision("target/integration-test-classes", "demo", null, "compiled integration-test classes"),
                new Collision("target/generated/sources/annotations", "demo", null, "generated main sources"),
                new Collision("target/generated/test-sources/annotations", "demo", null, "generated test sources"))) {
            NativeImageException exception = assertThrows(
                    NativeImageException.class,
                    () -> NativeOutputPlan.plan(
                            project,
                            config(collision.output(), collision.imageName(), ""),
                            collision.cacheRoot(),
                            Path.of("native-image")),
                    collision.toString());
            assertTrue(exception.getMessage().contains(collision.expectedKind()), exception.getMessage());
        }
    }

    @Test
    void rejectsHardLinkAliasIntoAnAuthoritativeSourceTree() throws IOException {
        Path source = project.resolve("src/main/java/com/example/Main.java");
        Path binary = project.resolve("target/native/demo");
        Files.createDirectories(source.getParent());
        Files.createDirectories(binary.getParent());
        Files.writeString(source, "source\n");
        Files.createLink(binary, source);

        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> NativeOutputPlan.plan(project, config("target/native", "demo", "")));

        assertTrue(exception.getMessage().contains("main source root"), exception.getMessage());
        assertTrue(Files.isSameFile(source, binary));
    }

    @Test
    void rejectsDeclaredGeneratedInputsAndOutputs() {
        String generated = """
                [generated.main.codegen]
                kind = "declared-root"
                language = "java"
                output = "custom/generated"
                inputs = ["schema/api.yaml"]
                """;

        NativeImageException outputException = assertThrows(
                NativeImageException.class,
                () -> NativeOutputPlan.plan(project, config("custom/generated", "demo", generated)));
        assertTrue(outputException.getMessage().contains("generated main output"), outputException.getMessage());

        NativeImageException inputException = assertThrows(
                NativeImageException.class,
                () -> NativeOutputPlan.plan(project, config("schema", "demo", generated)));
        assertTrue(inputException.getMessage().contains("generated main input"), inputException.getMessage());
    }

    private NativeBuildService service(List<List<String>> commands) {
        return new NativeBuildServiceTestSupport() {
        }.service(command -> {
            commands.add(command);
            throw new AssertionError("native-image should not run");
        });
    }

    private static ProjectConfig config(String output, String imageName, String extra) {
        return new ZoltTomlParser().parse("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"
                main = "com.example.Main"

                [native]
                output = "%s"
                imageName = "%s"

                %s
                """.formatted(output, imageName, extra));
    }

    private record Collision(String output, String imageName, Path cacheRoot, String expectedKind) {
    }
}
