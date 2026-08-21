package sh.zolt.cli.command.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.explain.verify.ResolvedModule;
import sh.zolt.explain.verify.VerifyScope;
import sh.zolt.explain.verify.ZoltModuleMapper;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveOptions;
import sh.zolt.resolve.ResolveService;
import sh.zolt.resolve.materialization.RepositoryOverlay;
import sh.zolt.toml.ZoltTomlParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Resolves a small fixture project fully offline against an on-disk Maven repository (via a
 * {@code maven-local} overlay), then maps the resolved lockfile with {@link ZoltModuleMapper}, to
 * verify the Zolt {@code compile}/{@code runtime}/{@code provided} scopes bucket exactly as the
 * comparison expects. This is the real resolution path — not synthetic lock packages.
 *
 * <p>The {@code test} scope is exercised by {@code ZoltModuleMapperTest} in the engine module using
 * lock packages directly: Zolt injects its JUnit platform console launcher into any real test scope,
 * whose transitive closure is not worth seeding into an offline fixture repository just to assert the
 * one-to-one {@code TEST} mapping.
 */
final class ZoltResolutionScopeMappingTest {
    private final ZoltModuleMapper mapper = new ZoltModuleMapper();

    @Test
    void resolvedScopesMapToTheComparedBuckets(@TempDir Path tempDir) {
        Path repository = tempDir.resolve("m2/repository");
        for (String artifact : List.of("compile-lib", "runtime-lib", "provided-lib")) {
            writeLocalArtifact(repository, "com.fixture", artifact, "1.0.0");
        }

        ProjectConfig config = new ZoltTomlParser().parse("""
                [project]
                name = "fixture"
                version = "9.9.9"
                group = "com.fixture"
                java = 21

                [repositories]
                central = "https://repo.maven.apache.org/maven2"

                [dependencies]
                "com.fixture:compile-lib" = "1.0.0"

                [dependencies.runtime]
                "com.fixture:runtime-lib" = "1.0.0"

                [dependencies.provided]
                "com.fixture:provided-lib" = "1.0.0"
                """);

        ResolveOptions options = new ResolveOptions(
                true, List.of(RepositoryOverlay.mavenLocal(repository)), false);
        List<LockPackage> packages = new ResolveService()
                .resolveLockfile(config, tempDir.resolve("cache"), options)
                .lockfile()
                .packages();

        ResolvedModule module = mapper.fromLockPackages("com.fixture", "fixture", "9.9.9", packages);

        assertEquals("com.fixture:fixture", module.moduleKey());
        assertEquals(List.of("com.fixture:compile-lib:1.0.0"), coordinates(module, VerifyScope.COMPILE));
        assertEquals(List.of("com.fixture:runtime-lib:1.0.0"), coordinates(module, VerifyScope.RUNTIME));
        assertEquals(List.of("com.fixture:provided-lib:1.0.0"), coordinates(module, VerifyScope.PROVIDED));
        assertEquals(List.of(), coordinates(module, VerifyScope.TEST));
    }

    private static List<String> coordinates(ResolvedModule module, VerifyScope scope) {
        return module.artifacts(scope).stream().map(artifact -> artifact.coordinate()).toList();
    }

    private static void writeLocalArtifact(Path root, String group, String artifact, String version) {
        String base = group.replace('.', '/') + "/" + artifact + "/" + version + "/" + artifact + "-" + version;
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(group, artifact, version);
        writeFile(root.resolve(base + ".pom"), pom.getBytes(StandardCharsets.UTF_8));
        writeFile(root.resolve(base + ".jar"), emptyJar());
    }

    private static byte[] emptyJar() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (JarOutputStream jar = new JarOutputStream(bytes)) {
                jar.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
                jar.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not build fixture jar bytes.", exception);
        }
    }

    private static void writeFile(Path path, byte[] content) {
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, content);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not write fixture artifact " + path + ".", exception);
        }
    }
}
