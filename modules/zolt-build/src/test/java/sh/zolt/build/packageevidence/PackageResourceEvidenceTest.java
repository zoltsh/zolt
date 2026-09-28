package sh.zolt.build.packageevidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.PackageException;
import sh.zolt.project.BuildMetadataSettings;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ResourceFilteringSettings;
import sh.zolt.project.ResourceMissingTokenPolicy;
import sh.zolt.project.ResourceTokenSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageResourceEvidenceTest {
    private final PackageResourceEvidence evidence = new PackageResourceEvidence();

    @TempDir
    private Path projectDir;

    @Test
    void collectsNonSourceResourceInputsInDeterministicOrder() throws IOException {
        write("src/main/resources/app.properties", "name=demo\n");
        write("src/main/resources/Ignored.java", "class Ignored {}\n");
        write("src/main/resources/Ignored.groovy", "class Ignored {}\n");
        write("src/main/resources/Ignored.kt", "class Ignored\n");
        write("src/main/resources/setup.kts", "println(\"setup\")\n");
        write("resources-extra/config.yml", "server: true\n");

        PackageResourceEvidence.ResourceEvidence result = evidence.collect(
                projectDir,
                build(List.of("resources-extra", "missing", "src/main/resources"), ResourceFilteringSettings.defaults()));

        assertEquals(List.of(
                projectDir.resolve("resources-extra/config.yml"),
                projectDir.resolve("src/main/resources/app.properties"),
                projectDir.resolve("src/main/resources/setup.kts")), result.inputs());
        assertTrue(result.fingerprint().startsWith("sha256:"));
    }

    @Test
    void recordsTokenSourceKindsWithoutTokenValues() throws IOException {
        ResourceFilteringSettings filtering = new ResourceFilteringSettings(
                true,
                false,
                List.of("**/*.properties"),
                ResourceMissingTokenPolicy.FAIL,
                Map.of(
                        "literalName", ResourceTokenSettings.literal("secret-name"),
                        "projectVersion", ResourceTokenSettings.project("version"),
                        "ciBuild", ResourceTokenSettings.env("CI_BUILD")));

        PackageResourceEvidence.ResourceEvidence result = evidence.collect(projectDir, build(List.of(), filtering));

        assertEquals(List.of(
                new PackageResourceEvidence.TokenSource("ciBuild", "env"),
                new PackageResourceEvidence.TokenSource("literalName", "literal"),
                new PackageResourceEvidence.TokenSource("projectVersion", "project")), result.tokenSources());
        assertTrue(result.fingerprint().startsWith("sha256:"));
    }

    @Test
    void fingerprintChangesWhenResourceContentsChange() throws IOException {
        Path resource = write("src/main/resources/app.properties", "name=demo\n");
        BuildSettings build = build(List.of("src/main/resources"), ResourceFilteringSettings.defaults());

        String first = evidence.collect(projectDir, build).fingerprint();
        Files.writeString(resource, "name=changed\n");
        String second = evidence.collect(projectDir, build).fingerprint();

        assertNotEquals(first, second);
    }

    @Test
    void unsafeResourceRootIsActionable() {
        PackageException exception = assertThrows(
                PackageException.class,
                () -> evidence.collect(projectDir, build(List.of("../outside"), ResourceFilteringSettings.defaults())));

        assertTrue(exception.getMessage().contains("[resources].main"));
        assertTrue(exception.getMessage().contains("../outside"));
    }

    private Path write(String path, String content) throws IOException {
        Path file = projectDir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static BuildSettings build(
            List<String> resourceRoots,
            ResourceFilteringSettings filtering) {
        return new BuildSettings(
                "src/main/java",
                "src/test/java",
                "target/classes",
                "target/test-classes",
                List.of("src/test/java"),
                List.of(),
                resourceRoots,
                List.of("src/test/resources"),
                filtering,
                BuildMetadataSettings.defaults());
    }
}
