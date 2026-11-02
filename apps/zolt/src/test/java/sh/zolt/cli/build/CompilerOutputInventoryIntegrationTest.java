package sh.zolt.cli.build;

import static org.junit.jupiter.api.Assertions.*;

import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.cache.BuildCacheSettings;
import sh.zolt.build.compile.JavacRunner;
import sh.zolt.build.output.CompilerOutputInventory;
import sh.zolt.build.packaging.PackageService;
import sh.zolt.build.testruntime.compile.TestCompileService;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real javac processors and ServiceLoader behavior across both compilation scopes. */
final class CompilerOutputInventoryIntegrationTest {
    private static final String DESCRIPTOR = "META-INF/services/java.lang.Runnable";
    @TempDir private Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repairsDeletedAndCorruptProcessorMetadata(boolean test) throws Exception {
        Fixture fixture = fixture(test, false);
        assertFalse(fixture.compile().skipped());
        assertEquals("processor", fixture.provider());
        assertTrue(fixture.compile().skipped());

        Files.delete(fixture.output().resolve(DESCRIPTOR));
        assertEquals("missing", fixture.provider());
        assertFalse(fixture.compile().skipped());
        assertEquals("processor", fixture.provider());

        Files.writeString(fixture.output().resolve(DESCRIPTOR), "not.a.Provider\n");
        assertThrows(ServiceConfigurationError.class, fixture::provider);
        assertFalse(fixture.compile().skipped());
        assertEquals("processor", fixture.provider());
        assertTrue(fixture.compile().skipped());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resourceEditsSkipCompilationAndOverlayRemovalRestoresProcessorOutput(boolean test) throws Exception {
        Fixture fixture = fixture(test, false);
        fixture.compile();
        Path resource = write("src/" + fixture.scope() + "/resources/" + DESCRIPTOR, "proof.AlternateProvider\n");
        assertTrue(fixture.compile().skipped(), "adding a resource overlay does not compile");
        assertEquals("resource", fixture.provider());
        Files.writeString(resource, "# changed resource\nproof.AlternateProvider\n");
        assertTrue(fixture.compile().skipped(), "resource-only edit must skip javac");
        assertTrue(fixture.compile().skipped(), "the following no-op must also skip javac");
        assertEquals("resource", fixture.provider());

        Files.delete(resource);
        assertFalse(fixture.compile().skipped(), "removing a masking overlay must regenerate compiler bytes");
        assertEquals("processor", fixture.provider());
        assertTrue(fixture.compile().skipped());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cacheRestoresPortableInventoryAndRepairsDamagedRestoredMetadata(boolean test) throws Exception {
        Fixture fixture = fixture(test, true);
        fixture.compile();
        byte[] inventory = Files.readAllBytes(fixture.output().resolve(CompilerOutputInventory.FILE_NAME));
        try (var paths = Files.walk(root.resolve("target"))) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
        Outcome restored = fixture.compile();
        assertTrue(restored.restored(), "clean output should restore from the cache");
        assertArrayEquals(inventory, Files.readAllBytes(fixture.output().resolve(CompilerOutputInventory.FILE_NAME)));
        assertEquals("processor", fixture.provider());
        assertTrue(fixture.compile().skipped());
        Files.delete(fixture.output().resolve(DESCRIPTOR));
        assertFalse(fixture.compile().skipped());
        assertEquals("processor", fixture.provider());
    }

    @ParameterizedTest
    @ValueSource(strings = {"main/missing", "test/missing", "main/corrupt", "test/corrupt"})
    void internallyInconsistentCacheEntriesRecompileInsteadOfBeingStampedCurrent(String scenario) throws Exception {
        boolean test = scenario.startsWith("test");
        Fixture fixture = fixture(test, true);
        fixture.compile();
        var scope = test ? sh.zolt.build.cache.BuildCacheScope.TEST : sh.zolt.build.cache.BuildCacheScope.MAIN;
        sh.zolt.build.cache.BuildCacheEntryMetadata metadata;
        try (var files = Files.walk(root.resolve("cache"))) {
            metadata = files.filter(path -> path.toString().endsWith(".zbc.meta"))
                    .map(path -> {
                        try { return sh.zolt.build.cache.BuildCacheEntryMetadata.parse(Files.readString(path)).orElseThrow(); }
                        catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); }
                    }).filter(value -> value.scope().equals(scope.id())).findFirst().orElseThrow();
        }
        if (scenario.endsWith("missing")) {
            Files.delete(fixture.output().resolve(CompilerOutputInventory.FILE_NAME));
        } else {
            Files.writeString(fixture.output().resolve(DESCRIPTOR), "not.a.Provider\n");
        }
        // Produce a checksum-valid cache archive whose compiler ownership/content is inconsistent.
        new sh.zolt.build.cache.LocalBuildCache(root.resolve("cache"), 0L).store(
                new sh.zolt.build.cache.BuildCacheKey(scope, metadata.key()), fixture.output(), "inventory-test");
        try (var files = Files.walk(fixture.output())) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) { Files.delete(path); }
        }
        Outcome repaired = fixture.compile();
        assertFalse(repaired.restored(), "a valid archive checksum does not prove a complete compiler output roster");
        assertFalse(repaired.skipped());
        assertEquals("processor", fixture.provider());
        assertTrue(fixture.compile().skipped());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void truncatedInventoryCannotAuthorizeReuse(boolean test) throws Exception {
        Fixture fixture = fixture(test, false);
        fixture.compile();
        Path state = fixture.output().resolve(CompilerOutputInventory.FILE_NAME);
        Files.writeString(state, "zolt-compiler-outputs-v1\n");
        Files.delete(fixture.output().resolve(DESCRIPTOR));
        assertFalse(fixture.compile().skipped());
        assertEquals("processor", fixture.provider());
        assertTrue(fixture.compile().skipped());
    }

    @Test
    void packageIncludesServiceDescriptorButNeverOwnershipState() throws Exception {
        Fixture fixture = fixture(false, false);
        BuildResult built = fixture.build.build(root, fixture.config, fixture.classpaths);
        var result = new PackageService().packageJar(root, fixture.config, built, fixture.classpaths);
        try (JarFile jar = new JarFile(result.jarPath().toFile())) {
            assertNotNull(jar.getEntry(DESCRIPTOR));
            assertNull(jar.getEntry(CompilerOutputInventory.FILE_NAME));
        }
    }

    @Test
    void unavailableGitMetadataDoesNotMaskProcessorProducedProperties() throws Exception {
        Fixture base = fixture(false, false);
        Fixture fixture = withGitMetadata(base, sh.zolt.provenance.BuildProvenanceSource.empty());
        fixture.compile();
        Path properties = fixture.output().resolve("git.properties");
        assertEquals("processor-git", Files.readString(properties));
        assertFalse(CompilerOutputInventory.read(fixture.output()).orElseThrow().overlays().contains(Path.of("git.properties")));
        Files.delete(properties);
        assertFalse(fixture.compile().skipped());
        assertEquals("processor-git", Files.readString(properties));
        Files.writeString(properties, "damaged");
        assertFalse(fixture.compile().skipped());
        assertEquals("processor-git", Files.readString(properties));
        assertTrue(fixture.compile().skipped());
    }

    @Test
    void disappearingMetadataProvenanceRestoresTheProcessorFile() throws Exception {
        Fixture base = fixture(false, false);
        var git = new java.util.concurrent.atomic.AtomicReference<>(new sh.zolt.provenance.GitProvenance(
                java.util.Optional.of("0123456789abcdef"), java.util.Optional.of("0123456"),
                java.util.Optional.of("main"), false, java.util.Optional.empty()));
        Fixture fixture = withGitMetadata(base, (project, environment, clock) -> new sh.zolt.provenance.BuildProvenance(
                git.get(), java.time.Instant.EPOCH, "test", "21", "test", java.util.Optional.empty()));
        fixture.compile();
        Path properties = fixture.output().resolve("git.properties");
        assertTrue(Files.readString(properties).contains("git.commit.id=0123456789abcdef"));
        assertTrue(fixture.compile().skipped());
        Files.writeString(properties, "damaged metadata overlay");
        assertTrue(fixture.compile().skipped(), "metadata repair does not require javac");
        assertTrue(Files.readString(properties).contains("git.commit.id=0123456789abcdef"));
        git.set(sh.zolt.provenance.GitProvenance.none());
        assertFalse(fixture.compile().skipped());
        assertEquals("processor-git", Files.readString(properties));
        assertTrue(fixture.compile().skipped());
    }

    @Test
    void coldCacheRegeneratesProcessorMetadataWhenItsGitOverlayDisappears() throws Exception {
        Fixture base = fixture(false, false);
        var git = new java.util.concurrent.atomic.AtomicReference<>(new sh.zolt.provenance.GitProvenance(
                java.util.Optional.of("0123456789abcdef"), java.util.Optional.of("0123456"),
                java.util.Optional.of("main"), false, java.util.Optional.empty()));
        Fixture metadata = withGitMetadata(base, (project, environment, clock) -> new sh.zolt.provenance.BuildProvenance(
                git.get(), java.time.Instant.EPOCH, "test", "21", "test", java.util.Optional.empty()));
        BuildCacheService cache = BuildCacheService.create(
                new BuildCacheSettings(true, root.resolve("cache"), 0L), "inventory-test");
        Fixture fixture = new Fixture(false, metadata.config, metadata.classpaths,
                metadata.build.withBuildCache(cache), metadata.tests);
        fixture.compile();
        for (boolean overlayAvailable : List.of(true, false)) {
            if (!overlayAvailable) {
                git.set(sh.zolt.provenance.GitProvenance.none());
            }
            try (var files = Files.walk(root.resolve("target"))) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
            Outcome result = fixture.compile();
            assertEquals(overlayAvailable, result.restored(), "only currently masked compiler bytes may restore");
            String properties = Files.readString(fixture.output().resolve("git.properties"));
            if (overlayAvailable) {
                assertTrue(properties.contains("git.commit.id=0123456789abcdef"));
            } else {
                assertFalse(result.skipped(), "the real annotation processor must regenerate its unmasked output");
                assertEquals("processor-git", properties);
            }
            assertEquals("processor", fixture.provider());
            assertTrue(fixture.compile().skipped(), "the following warm build must be current");
        }
    }

    private Fixture withGitMetadata(Fixture fixture, sh.zolt.provenance.BuildProvenanceSource provenance) throws Exception {
        String manifest = Files.readString(root.resolve("zolt.toml")) + "\n[build.metadata]\ngit = true\n";
        write("zolt.toml", manifest);
        return new Fixture(false, new ManifestProjectConfigLoader().load(manifest), fixture.classpaths,
                new BuildService(provenance), fixture.tests);
    }

    @Test
    void workspaceResourceOverlayEditsAndFollowingNoOpsPreserveProcessorOwnership() throws Exception {
        fixture(false, false);
        Path member = root.resolve("app");
        Files.createDirectories(member);
        Files.move(root.resolve("src"), member.resolve("src"));
        Files.move(root.resolve("zolt.toml"), member.resolve("zolt.toml"));
        Files.writeString(member.resolve("zolt.toml"), Files.readString(member.resolve("zolt.toml"))
                + "\n[dependencies.processor]\n\"proof:processor\" = { workspace = true }\n");
        write("zolt.toml", "[workspace]\nname = \"metadata\"\n[workspace.members]\ninclude = [\"processor\", \"app\"]\n");
        Files.delete(root.resolve("zolt.lock"));
        write("processor/zolt.toml", "[project]\nname = \"processor\"\nversion = \"1.0.0\"\ngroup = \"proof\"\njava = 21\n");
        write("processor/src/main/java/proof/MetadataProcessor.java", Files.readString(root.resolve("processor-src/proof/MetadataProcessor.java")));
        write("processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor", "proof.MetadataProcessor\n");
        var service = new sh.zolt.workspace.service.WorkspaceBuildService();
        assertFalse(workspaceBuild(service).mainCompilationSkipped());
        assertTrue(workspaceBuild(service).mainCompilationSkipped());
        Path resource = write("app/src/main/resources/" + DESCRIPTOR, "proof.AlternateProvider\n");
        assertTrue(workspaceBuild(service).mainCompilationSkipped());
        Files.writeString(resource, "# edited overlay\nproof.AlternateProvider\n");
        assertTrue(workspaceBuild(service).mainCompilationSkipped());
        assertTrue(workspaceBuild(service).mainCompilationSkipped(), "clean-member finalization must not stale compiler hashes");
        assertEquals("resource", provider(member.resolve("target/classes")));
        Files.delete(resource);
        assertFalse(workspaceBuild(service).mainCompilationSkipped());
        assertEquals("processor", provider(member.resolve("target/classes")));
        assertTrue(workspaceBuild(service).mainCompilationSkipped());
        Files.delete(member.resolve("target/classes").resolve(DESCRIPTOR));
        assertFalse(workspaceBuild(service).mainCompilationSkipped(), "workspace output gate must see lost processor resources");
        assertEquals("processor", provider(member.resolve("target/classes")));
    }

    private BuildResult workspaceBuild(sh.zolt.workspace.service.WorkspaceBuildService service) {
        return service.build(root, root.resolve("artifacts"), true).members().stream()
                .filter(member -> member.member().equals("app")).findFirst().orElseThrow().result();
    }

    private static String provider(Path output) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {output.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            return ServiceLoader.load(Runnable.class, loader).findFirst().map(Object::toString).orElse("missing");
        }
    }

    private Fixture fixture(boolean test, boolean cache) throws Exception {
        Path processor = processorJar();
        String scope = test ? "test" : "main";
        write("src/" + scope + "/java/proof/GeneratedProvider.java", provider("GeneratedProvider", "processor"));
        write("src/" + scope + "/java/proof/AlternateProvider.java", provider("AlternateProvider", "resource"));
        String manifest = """
                [project]
                name = "owned-outputs"
                version = "1.0.0"
                group = "proof"
                java = 21
                """;
        write("zolt.toml", manifest);
        write("zolt.lock", "version = 7\n");
        ProjectConfig config = new ManifestProjectConfigLoader().load(manifest);
        Classpath empty = new Classpath(List.of());
        Classpath processors = new Classpath(List.of(processor));
        ClasspathSet classpaths = new ClasspathSet(empty, empty, empty, empty,
                test ? empty : processors, test ? processors : empty, empty);
        BuildCacheService outputCache = cache ? BuildCacheService.create(
                new BuildCacheSettings(true, root.resolve("cache"), 0L), "inventory-test") : BuildCacheService.disabled();
        return new Fixture(test, config, classpaths, new BuildService().withBuildCache(outputCache),
                new TestCompileService().withBuildCache(outputCache));
    }

    private Path processorJar() throws Exception {
        Path source = write("processor-src/proof/MetadataProcessor.java", """
                package proof;
                @javax.annotation.processing.SupportedAnnotationTypes("*")
                public final class MetadataProcessor extends javax.annotation.processing.AbstractProcessor {
                    private boolean written;
                    @Override public javax.lang.model.SourceVersion getSupportedSourceVersion() {
                        return javax.lang.model.SourceVersion.latestSupported();
                    }
                    @Override public boolean process(java.util.Set<? extends javax.lang.model.element.TypeElement> annotations,
                            javax.annotation.processing.RoundEnvironment round) {
                        if (!written && !round.processingOver()) {
                            written = true;
                            try (var writer = processingEnv.getFiler().createResource(
                                    javax.tools.StandardLocation.CLASS_OUTPUT, "", "META-INF/services/java.lang.Runnable")
                                    .openWriter()) {
                                writer.write("proof.GeneratedProvider\\n");
                            } catch (java.io.IOException exception) { throw new RuntimeException(exception); }
                            try (var writer = processingEnv.getFiler().createResource(
                                    javax.tools.StandardLocation.CLASS_OUTPUT, "", "git.properties").openWriter()) {
                                writer.write("processor-git");
                            } catch (java.io.IOException exception) { throw new RuntimeException(exception); }
                        }
                        return false;
                    }
                }
                """);
        Path classes = root.resolve("processor-classes");
        new JavacRunner().compile(Path.of(System.getProperty("java.home"), "bin", "javac"),
                List.of(source), new Classpath(List.of()), classes);
        write("processor-classes/META-INF/services/javax.annotation.processing.Processor", "proof.MetadataProcessor\n");
        Path jar = root.resolve("processor.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar));
                var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                output.write(Files.readAllBytes(file));
                output.closeEntry();
            }
        }
        return jar;
    }

    private static String provider(String name, String value) {
        return "package proof; public final class " + name + " implements Runnable { "
                + "public void run() {} public String toString() { return \"" + value + "\"; } }\n";
    }

    private Path write(String relative, String content) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    private record Outcome(boolean skipped, boolean restored) {}

    private final class Fixture {
        private final boolean test;
        private final ProjectConfig config;
        private final ClasspathSet classpaths;
        private final BuildService build;
        private final TestCompileService tests;

        Fixture(boolean test, ProjectConfig config, ClasspathSet classpaths, BuildService build, TestCompileService tests) {
            this.test = test; this.config = config; this.classpaths = classpaths; this.build = build; this.tests = tests;
        }

        String scope() { return test ? "test" : "main"; }
        Path output() { return root.resolve(test ? "target/test-classes" : "target/classes"); }

        Outcome compile() {
            BuildResult main = build.build(root, config, classpaths);
            if (!test) {
                return new Outcome(main.mainCompilationSkipped(), main.mainCompilationRestored());
            }
            var compiled = tests.compileTests(root, config, classpaths, main);
            return new Outcome(compiled.testCompilationSkipped(), "restored".equals(compiled.testCompilationMode()));
        }

        String provider() throws Exception {
            return CompilerOutputInventoryIntegrationTest.provider(output());
        }
    }
}
