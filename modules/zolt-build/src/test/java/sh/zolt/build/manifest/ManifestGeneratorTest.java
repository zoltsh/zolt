package sh.zolt.build.manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.ManifestGenerationException;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.provenance.BuildProvenance;
import sh.zolt.provenance.GitProvenance;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ManifestGeneratorTest {
    private static final String COMMIT_SHA = "0123456789abcdef0123456789abcdef01234567";

    private final ManifestGenerator generator = new ManifestGenerator();

    @TempDir
    private Path projectDir;

    @Test
    void manifestIncludesMainClassWhenProjectMainIsConfigured() throws IOException {
        GeneratedManifest generated = generator.generate(project(Optional.of("com.example.Main")));
        Manifest manifest = parse(generated);
        Attributes attributes = manifest.getMainAttributes();

        assertEquals(GeneratedManifest.DEFAULT_PATH, generated.path());
        assertEquals("1.0", attributes.getValue(Attributes.Name.MANIFEST_VERSION));
        assertEquals("com.example.Main", attributes.getValue(Attributes.Name.MAIN_CLASS));
        assertEquals("com.example.Main", generated.mainClass().orElseThrow());
    }

    @Test
    void libraryProjectsGetManifestWithoutMainClass() throws IOException {
        GeneratedManifest generated = generator.generate(project(Optional.empty()));
        Manifest manifest = parse(generated);
        Attributes attributes = manifest.getMainAttributes();

        assertEquals("1.0", attributes.getValue(Attributes.Name.MANIFEST_VERSION));
        assertFalse(generated.mainClass().isPresent());
        assertFalse(attributes.containsKey(Attributes.Name.MAIN_CLASS));
    }

    @Test
    void includesSortedCustomManifestAttributes() throws IOException {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("Bundle-SymbolicName", "com.example.demo");
        attributes.put("Automatic-Module-Name", "com.example.demo");

        GeneratedManifest generated = generator.generate(project(Optional.of("com.example.Main")), attributes);
        Manifest manifest = parse(generated);
        Attributes mainAttributes = manifest.getMainAttributes();
        String content = new String(generated.content(), StandardCharsets.UTF_8);

        assertEquals("com.example.demo", mainAttributes.getValue("Automatic-Module-Name"));
        assertEquals("com.example.demo", mainAttributes.getValue("Bundle-SymbolicName"));
        assertTrue(content.indexOf("Automatic-Module-Name") < content.indexOf("Bundle-SymbolicName"));
        assertTrue(content.indexOf("Main-Class") < content.indexOf("Automatic-Module-Name"));
    }

    @Test
    void includesProvenanceAttributesForPackagedArtifacts() throws IOException {
        writeGitMetadata(projectDir, COMMIT_SHA);
        ManifestGenerator generator = new ManifestGenerator(
                Clock.fixed(Instant.parse("2026-06-08T00:00:00Z"), ZoneOffset.UTC),
                Map.of("SOURCE_DATE_EPOCH", "1700000000"));

        GeneratedManifest generated = generator.generate(projectDir, config(true));
        Attributes attributes = parse(generated).getMainAttributes();

        assertEquals("0.1.0", attributes.getValue("Implementation-Version"));
        assertEquals(COMMIT_SHA, attributes.getValue("SCM-Revision"));
        assertEquals("2023-11-14T22:13:20Z", attributes.getValue("Build-Timestamp"));
        assertFalse(attributes.getValue("Build-Jdk").isBlank());
    }

    @Test
    void includesZoltBuilderProvenanceWhenSupplied() throws IOException {
        ManifestGenerator generator = new ManifestGenerator(
                Clock.fixed(Instant.parse("2026-07-07T12:00:00Z"), ZoneOffset.UTC),
                (projectRoot, environment, clock) -> provenance(clock.instant()),
                Map.of());

        GeneratedManifest generated = generator.generate(projectDir, config(false));
        Attributes attributes = parse(generated).getMainAttributes();

        assertEquals("Zolt 0.1.0-zap.20260707.abcdef123456", attributes.getValue("Created-By"));
        assertEquals("0.1.0-zap.20260707.abcdef123456", attributes.getValue("Zolt-Version"));
        assertEquals("sha256:manifest-inputs", attributes.getValue("Zolt-Resolution-Fingerprint"));
    }

    @Test
    void reproducibleManifestFallsBackToEpochWhenSourceDateEpochIsMissing() throws IOException {
        ManifestGenerator generator = new ManifestGenerator(
                Clock.fixed(Instant.parse("2026-06-08T00:00:00Z"), ZoneOffset.UTC),
                Map.of());

        GeneratedManifest generated = generator.generate(projectDir, config(true));
        Attributes attributes = parse(generated).getMainAttributes();

        assertEquals("1970-01-01T00:00:00Z", attributes.getValue("Build-Timestamp"));
    }

    @Test
    void nonReproducibleManifestUsesClockEvenWhenSourceDateEpochIsPresent() throws IOException {
        ManifestGenerator generator = new ManifestGenerator(
                Clock.fixed(Instant.parse("2026-06-08T00:00:00Z"), ZoneOffset.UTC),
                Map.of("SOURCE_DATE_EPOCH", "1700000000"));

        GeneratedManifest generated = generator.generate(projectDir, config(false));
        Attributes attributes = parse(generated).getMainAttributes();

        assertEquals("2026-06-08T00:00:00Z", attributes.getValue("Build-Timestamp"));
    }

    @Test
    void foldsLongManifestAttributeValuesDeterministically() throws IOException {
        GeneratedManifest generated = generator.generate(
                project(Optional.empty()),
                Map.of("Export-Package", "com.example.alpha;version=\"1.0.0\",com.example.beta;version=\"1.0.0\""));

        Manifest manifest = parse(generated);
        String content = new String(generated.content(), StandardCharsets.UTF_8);

        assertEquals(
                "com.example.alpha;version=\"1.0.0\",com.example.beta;version=\"1.0.0\"",
                manifest.getMainAttributes().getValue("Export-Package"));
        assertTrue(content.contains("\r\n "));
    }

    @Test
    void rejectsManifestDefaultsAsCustomAttributes() {
        ManifestGenerationException exception = assertThrows(
                ManifestGenerationException.class,
                () -> generator.generate(project(Optional.empty()), Map.of("Main-Class", "com.example.Other")));

        assertTrue(exception.getMessage().contains("Zolt owns Manifest-Version and Main-Class"));
    }

    @Test
    void rejectsProvenanceAttributesAsCustomAttributes() {
        ManifestGenerationException exception = assertThrows(
                ManifestGenerationException.class,
                () -> generator.generate(project(Optional.empty()), Map.of("SCM-Revision", COMMIT_SHA)));

        assertTrue(exception.getMessage().contains("Zolt owns Implementation-Version"));
    }

    @Test
    void rejectsMalformedManifestAttributeNames() {
        ManifestGenerationException exception = assertThrows(
                ManifestGenerationException.class,
                () -> generator.generate(project(Optional.empty()), Map.of("Bad Name", "value")));

        assertTrue(exception.getMessage().contains("Manifest attribute names must contain only"));
    }

    @Test
    void rejectsUnsafeManifestAttributeValues() {
        ManifestGenerationException exception = assertThrows(
                ManifestGenerationException.class,
                () -> generator.generate(project(Optional.empty()), Map.of("Bundle-Name", "bad\nvalue")));

        assertTrue(exception.getMessage().contains("Manifest values cannot contain line breaks"));
    }

    @Test
    void rejectsCaseInsensitiveDuplicateManifestAttributeNames() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("Bundle-Name", "demo");
        attributes.put("bundle-name", "duplicate");

        ManifestGenerationException exception = assertThrows(
                ManifestGenerationException.class,
                () -> generator.generate(project(Optional.empty()), attributes));

        assertTrue(exception.getMessage().contains("unique ignoring case"));
    }

    @Test
    void contentAccessorReturnsDefensiveCopy() {
        GeneratedManifest generated = generator.generate(project(Optional.of("com.example.Main")));
        byte[] first = generated.content();
        first[0] = 0;

        assertArrayEquals(generator.generate(project(Optional.of("com.example.Main"))).content(), generated.content());
    }

    private static ProjectMetadata project(Optional<String> mainClass) {
        return new ProjectMetadata("demo", "0.1.0", "com.example", "21", mainClass);
    }

    private static ProjectConfig config(boolean reproducible) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21
                main = "com.example.Main"

                [build.metadata]
                reproducible = %s
                """.formatted(reproducible));
    }

    private static BuildProvenance provenance(Instant instant) {
        return new BuildProvenance(
                new GitProvenance(
                        Optional.of(COMMIT_SHA),
                        Optional.of("0123456789ab"),
                        Optional.of("main"),
                        false,
                        Optional.empty()),
                instant,
                "0.1.0-zap.20260707.abcdef123456",
                "21.0.2",
                "Eclipse Adoptium",
                Optional.of("sha256:manifest-inputs"));
    }

    private static void writeGitMetadata(Path projectDir, String sha) throws IOException {
        Path head = projectDir.resolve(".git/HEAD");
        Path branch = projectDir.resolve(".git/refs/heads/main");
        Files.createDirectories(branch.getParent());
        Files.writeString(head, "ref: refs/heads/main\n");
        Files.writeString(branch, sha + "\n");
    }

    private static Manifest parse(GeneratedManifest generated) throws IOException {
        return new Manifest(new ByteArrayInputStream(generated.content()));
    }
}
