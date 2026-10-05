package sh.zolt.build.generatedsource;

import static sh.zolt.build.generatedsource.OpenApiGeneratedSourceServiceTestSupport.configWithAdditionalProperties;
import static sh.zolt.build.generatedsource.OpenApiGeneratedSourceServiceTestSupport.multiSpecConfig;
import static sh.zolt.build.generatedsource.OpenApiGeneratedSourceServiceTestSupport.packages;
import static sh.zolt.build.generatedsource.OpenApiGeneratedSourceServiceTestSupport.service;
import static sh.zolt.build.generatedsource.OpenApiGeneratedSourceServiceTestSupport.writeGeneratedInterface;
import static sh.zolt.build.generatedsource.OpenApiGeneratedSourceServiceTestSupport.writeProjectFiles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import sh.zolt.build.BuildException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OpenApiGeneratedSourceServiceTest {
    @TempDir
    private Path projectDir;

    @Test
    void runsOpenApiGeneratorBeforeSourceDiscoveryAndKeepsToolingOutOfApplicationClasspaths() throws IOException {
        writeProjectFiles(projectDir);
        List<List<String>> commands = new ArrayList<>();
        OpenApiGeneratedSourceService service = service(projectDir, (command, directory) -> {
            commands.add(command);
            try {
                writeGeneratedInterface(Path.of(command.get(command.indexOf("--output") + 1)));
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
            return new OpenApiGeneratedSourceService.ProcessResult(0, "generated\n");
        });

        service.generateMain(projectDir, configWithAdditionalProperties(Map.of("sourceFolder", ".")), packages(projectDir));

        assertEquals(1, commands.size());
        List<String> command = commands.getFirst();
        assertEquals(projectDir.resolve("fake-java").toString(), command.getFirst());
        assertTrue(command.contains("org.openapitools.codegen.OpenAPIGenerator"));
        assertTrue(command.contains("--input-spec"));
        assertTrue(command.contains(projectDir.resolve("src/main/openapi/public-api.yaml").toString()));
        assertTrue(command.contains("--generator-name"));
        assertTrue(command.contains("spring"));
        assertTrue(command.contains("--additional-properties"));
        assertTrue(command.get(command.indexOf("--additional-properties") + 1).contains("sourceFolder=."));
        assertFalse(command.get(command.indexOf("-cp") + 1).contains("app-1.0.0.jar"));
        assertTrue(Files.exists(projectDir.resolve(
                "target/generated/sources/openapi/public-api/com/example/generated/PublicApi.java")));
        assertTrue(Files.exists(projectDir.resolve(
                "target/generated/sources/openapi/public-api/.zolt-openapi-main-public-api.fingerprint")));
        assertTrue(Files.exists(projectDir.resolve(
                "target/generated/sources/openapi/public-api/.zolt-openapi-main-public-api.log")));
    }

    @Test
    void skipsGenerationWhenFingerprintIsCurrent() throws IOException {
        writeProjectFiles(projectDir);
        List<List<String>> commands = new ArrayList<>();
        OpenApiGeneratedSourceService service = service(projectDir, (command, directory) -> {
            commands.add(command);
            try {
                writeGeneratedInterface(Path.of(command.get(command.indexOf("--output") + 1)));
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
            return new OpenApiGeneratedSourceService.ProcessResult(0, "generated\n");
        });

        service.generateMain(projectDir, configWithAdditionalProperties(Map.of("sourceFolder", ".")), packages(projectDir));
        service.generateMain(projectDir, configWithAdditionalProperties(Map.of("sourceFolder", ".")), packages(projectDir));

        assertEquals(1, commands.size());
    }

    @Test
    void regeneratesAndRepairsChangedOrDeletedOutput() throws IOException {
        writeProjectFiles(projectDir);
        List<List<String>> commands = new ArrayList<>();
        OpenApiGeneratedSourceService service = service(projectDir, (command, directory) -> {
            commands.add(command);
            try {
                writeGeneratedInterface(Path.of(command.get(command.indexOf("--output") + 1)));
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
            return new OpenApiGeneratedSourceService.ProcessResult(0, "generated\n");
        });
        Path generated = projectDir.resolve(
                "target/generated/sources/openapi/public-api/com/example/generated/PublicApi.java");

        service.generateMain(projectDir, configWithAdditionalProperties(Map.of("sourceFolder", ".")), packages(projectDir));
        String expected = Files.readString(generated);
        Files.writeString(generated, "package com.example.generated; public interface Changed {}\n");

        service.generateMain(projectDir, configWithAdditionalProperties(Map.of("sourceFolder", ".")), packages(projectDir));

        assertEquals(2, commands.size());
        assertEquals(expected, Files.readString(generated));

        Files.delete(generated);

        service.generateMain(projectDir, configWithAdditionalProperties(Map.of("sourceFolder", ".")), packages(projectDir));

        assertEquals(3, commands.size());
        assertEquals(expected, Files.readString(generated));
    }

    @Test
    void runsMultipleSpecsWithSharedPresetAndDeterministicOptionArguments() throws IOException {
        writeProjectFiles(projectDir);
        Files.writeString(projectDir.resolve("src/main/openapi/internal-api.yaml"), "openapi: 3.1.0\n");
        List<List<String>> commands = new ArrayList<>();
        OpenApiGeneratedSourceService service = service(projectDir, (command, directory) -> {
            commands.add(command);
            try {
                writeGeneratedInterface(Path.of(command.get(command.indexOf("--output") + 1)));
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
            return new OpenApiGeneratedSourceService.ProcessResult(0, "generated\n");
        });

        service.generateMain(projectDir, multiSpecConfig(), packages(projectDir));

        assertEquals(2, commands.size());
        assertTrue(commands.stream().anyMatch(command -> command.contains(projectDir.resolve(
                "src/main/openapi/public-api.yaml").toString())));
        assertTrue(commands.stream().anyMatch(command -> command.contains(projectDir.resolve(
                "src/main/openapi/internal-api.yaml").toString())));

        List<String> publicCommand = commands.stream()
                .filter(command -> command.contains(projectDir.resolve("src/main/openapi/public-api.yaml").toString()))
                .findFirst()
                .orElseThrow();
        assertTrue(publicCommand.contains("--skip-validate-spec"));
        assertEquals(
                "additionalModelTypeAnnotations=@lombok.Builder;@lombok.NoArgsConstructor,interfaceOnly=true,sourceFolder=.,useSpringBoot3=true",
                optionValue(publicCommand, "--additional-properties"));
        assertEquals("apis=true,models=true", optionValue(publicCommand, "--global-property"));
        assertEquals("OffsetDateTime=Instant,UUID=String", optionValue(publicCommand, "--type-mappings"));
        assertEquals("Instant=java.time.Instant,String=java.lang.String", optionValue(publicCommand, "--import-mappings"));
        assertTrue(publicCommand.contains("--api-package"));
        assertTrue(publicCommand.contains("com.example.publicapi"));
        assertTrue(publicCommand.contains("--model-package"));
        assertTrue(publicCommand.contains("com.example.publicmodel"));
    }

    @Test
    void regeneratesWhenSpecOrOptionsChange() throws IOException {
        writeProjectFiles(projectDir);
        List<List<String>> commands = new ArrayList<>();
        OpenApiGeneratedSourceService service = service(projectDir, (command, directory) -> {
            commands.add(command);
            try {
                writeGeneratedInterface(Path.of(command.get(command.indexOf("--output") + 1)));
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
            return new OpenApiGeneratedSourceService.ProcessResult(0, "generated\n");
        });

        service.generateMain(projectDir, configWithAdditionalProperties(Map.of("sourceFolder", ".")), packages(projectDir));
        service.generateMain(projectDir, configWithAdditionalProperties(Map.of("sourceFolder", ".")), packages(projectDir));
        Files.writeString(projectDir.resolve("src/main/openapi/public-api.yaml"), "openapi: 3.1.0\ninfo:\n  title: Changed\n");
        service.generateMain(projectDir, configWithAdditionalProperties(Map.of("sourceFolder", ".")), packages(projectDir));
        service.generateMain(
                projectDir,
                configWithAdditionalProperties(Map.of("sourceFolder", ".", "interfaceOnly", "true")),
                packages(projectDir));

        assertEquals(3, commands.size());
    }

    @Test
    void rejectsSymlinkedOutputBeforeDeletingTarget() throws IOException {
        writeProjectFiles(projectDir);
        Path outside = Files.createTempDirectory(projectDir.getParent(), "outside-openapi-output-");
        Files.writeString(outside.resolve("sentinel.txt"), "keep");
        Path output = projectDir.resolve("target/generated/sources/openapi/public-api");
        createSymlink(output, outside);
        List<List<String>> commands = new ArrayList<>();
        OpenApiGeneratedSourceService service = service(projectDir, (command, directory) -> {
            commands.add(command);
            return new OpenApiGeneratedSourceService.ProcessResult(0, "generated\n");
        });

        BuildException exception = assertThrows(
                BuildException.class,
                () -> service.generateMain(projectDir, configWithAdditionalProperties(Map.of("sourceFolder", ".")), packages(projectDir)));

        assertTrue(exception.getMessage().contains("Invalid OpenAPI output path `target/generated/sources/openapi/public-api`"));
        assertTrue(exception.getMessage().contains("[generated.main.public-api].output"));
        assertTrue(exception.getMessage().contains("resolved through symlinks"));
        assertTrue(Files.exists(outside.resolve("sentinel.txt")));
        assertTrue(commands.isEmpty());
    }

    private static String optionValue(List<String> command, String option) {
        return command.get(command.indexOf(option) + 1);
    }

    private static void createSymlink(Path link, Path target) throws IOException {
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception.getMessage());
        }
    }
}
