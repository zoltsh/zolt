package sh.zolt.build.packaging;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.PackageException;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageServiceJavadocToolchainTest {
    @TempDir
    private Path root;

    @Test
    void selectedJdkProducesSourcesAndJavadocInsteadOfUsingLauncherHome() throws Exception {
        ProjectConfig config = project(true);
        Path marker = root.resolve("selected-javadoc.txt");
        Path selected = selectedJdk("printf '%s\\n' \"$@\" >> " + quote(marker) + "\nexec "
                + quote(Path.of(System.getProperty("java.home"), "bin", "javadoc")) + " \"$@\"\n");

        PackageResult result = new PackageService().withJdkChecker(checker(selected))
                .packageJar(root, config, built(), root.resolve("cache"));

        assertTrue(Files.readString(marker).contains("-quiet"), "selected tool must generate the documentation");
        assertEquals(2, result.artifacts().size());
        try (JarFile sources = new JarFile(root.resolve("target/docs-1.0.0-sources.jar").toFile());
                JarFile docs = new JarFile(root.resolve("target/docs-1.0.0-javadoc.jar").toFile())) {
            assertNotNull(sources.getEntry("review/Api.java"));
            assertNotNull(docs.getEntry("review/Api.html"));
        }
    }

    @Test
    void missingSelectedJavadocPreservesAllPackageOutputs() throws Exception {
        ProjectConfig config = project(true);
        Path selected = selectedJdk(null);
        Map<Path, byte[]> previous = previousOutputs();

        PackageException failure = assertThrows(PackageException.class, () ->
                new PackageService().withJdkChecker(checker(selected)).packageJar(root, config, built(), root.resolve("cache")));

        assertTrue(failure.getMessage().contains(selected.resolve("bin/javadoc").toString()), failure.getMessage());
        assertNotNull(failure.actionableError());
        assertPreserved(previous);
    }

    @Test
    void unusableSelectedJavadocIsRejectedBeforeAnyPackageOutputChanges() throws Exception {
        ProjectConfig config = project(true);
        Path selected = selectedJdk("echo broken-tool >&2\nexit 7\n");
        Map<Path, byte[]> previous = previousOutputs();

        PackageException failure = assertThrows(PackageException.class, () ->
                new PackageService().withJdkChecker(checker(selected)).packageJar(root, config, built(), root.resolve("cache")));

        assertTrue(failure.getMessage().contains("code 7"), failure.getMessage());
        assertTrue(failure.getMessage().contains("broken-tool"), failure.getMessage());
        assertPreserved(previous);
    }

    @Test
    void disabledJavadocDoesNotResolveADocumentationTool() throws Exception {
        ProjectConfig config = project(false);
        PackageResult result = new PackageService().withJdkChecker(required -> {
            throw new AssertionError("Packaging existing classes without Javadoc must not resolve a tool.");
        }).packageJar(root, config, built(), root.resolve("cache"));
        assertTrue(Files.isRegularFile(result.jarPath()));
    }

    private ProjectConfig project(boolean docs) throws Exception {
        Path source = root.resolve("src/main/java/review/Api.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package review; /** Library API. */ public final class Api {}\n");
        Files.createDirectories(root.resolve("target/classes"));
        Files.writeString(root.resolve("zolt.lock"), "version = 7\n");
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "docs"
                version = "1.0.0"
                group = "review"
                java = 21
                [package]
                sources = true
                javadoc = %s
                """.formatted(docs));
    }

    private BuildResult built() {
        return new BuildResult(Optional.empty(), 1, 0, root.resolve("target/classes"), "");
    }

    private Path selectedJdk(String javadocScript) throws Exception {
        Path home = root.resolve("selected JDK");
        Files.createDirectories(home.resolve("bin"));
        if (javadocScript != null) {
            Path executable = home.resolve("bin/javadoc");
            Files.writeString(executable, "#!/bin/sh\n" + javadocScript);
            assertTrue(executable.toFile().setExecutable(true));
        }
        return home;
    }

    private static JdkChecker checker(Path home) {
        return required -> new JdkStatus(Optional.of(home), Optional.of(home.resolve("bin/java")),
                Optional.of(home.resolve("bin/javac")), Optional.of(home.resolve("bin/jar")),
                Optional.of("21"), required);
    }

    private Map<Path, byte[]> previousOutputs() throws Exception {
        Map<Path, byte[]> previous = new LinkedHashMap<>();
        for (String file : new String[] {"docs-1.0.0.jar", "docs-1.0.0-sources.jar",
                "docs-1.0.0-javadoc.jar", "docs-1.0.0.jar.zolt-package.json", "javadoc/keep.txt"}) {
            Path path = root.resolve("target").resolve(file);
            Files.createDirectories(path.getParent());
            byte[] bytes = ("previous " + file).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(path, bytes);
            previous.put(path, bytes);
        }
        return previous;
    }

    private static void assertPreserved(Map<Path, byte[]> previous) throws Exception {
        for (var file : previous.entrySet()) {
            assertArrayEquals(file.getValue(), Files.readAllBytes(file.getKey()), file.getKey().toString());
        }
    }

    private static String quote(Path path) {
        return "'" + path.toString().replace("'", "'\"'\"'") + "'";
    }
}
