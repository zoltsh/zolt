package sh.zolt.build.fingerprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildFingerprintExpectedClassesTest {
    @TempDir
    private Path projectDir;

    private final BuildFingerprintExpectedClasses expectedClasses = new BuildFingerprintExpectedClasses();

    @Test
    void inventoriesEveryCompilerOutputAndExcludesResources() throws IOException {
        Path output = projectDir.resolve("target/classes");
        for (String name : List.of("p/Main.class", "p/Main$Nested.class", "p/Main$1.class",
                "p/Additional.class", "META-INF/app.kotlin_module", "application.properties")) {
            Path file = output.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "compiled");
        }

        assertEquals(List.of(
                "target/classes/META-INF/app.kotlin_module",
                "target/classes/p/Additional.class",
                "target/classes/p/Main$1.class",
                "target/classes/p/Main$Nested.class",
                "target/classes/p/Main.class"), expectedClasses.entries(projectDir, output));
    }

    @Test
    void missingOutputDirectoryHasNoInventedCompilerOutputs() {
        assertTrue(expectedClasses.entries(projectDir, projectDir.resolve("target/classes")).isEmpty());
    }

    @Test
    void reportsMissingClassesRecordedInFingerprint() throws IOException {
        Path output = projectDir.resolve("target/classes");
        Path expectedClass = output.resolve("com/example/Main.class");
        String fingerprint = "[expectedClasses]\ntarget/classes/com/example/Main.class\n";
        assertEquals(List.of(expectedClass), expectedClasses.missing(projectDir, fingerprint));
        Files.createDirectories(expectedClass.getParent());
        Files.writeString(expectedClass, "compiled");
        assertTrue(expectedClasses.missing(projectDir, fingerprint).isEmpty());
    }

    @Test
    void rejectsChangedOrAdditionalClassOutputs() throws IOException {
        Path output = projectDir.resolve("target/classes");
        Files.createDirectories(output);
        Path main = output.resolve("Main.class");
        Files.writeString(main, "compiled");
        String fingerprint = "[expectedClasses]\n"
                + String.join("\n", expectedClasses.entries(projectDir, output))
                + "\n[compilerOutputHashes]\n"
                + String.join("\n", expectedClasses.hashes(projectDir, output)) + "\n";
        assertTrue(expectedClasses.recordedOutputsCurrent(projectDir, output, fingerprint));
        String incomplete = fingerprint.substring(0, fingerprint.indexOf("[compilerOutputHashes]"))
                + "[compilerOutputHashes]\n";
        assertFalse(expectedClasses.recordedOutputsCurrent(projectDir, output, incomplete));
        Files.writeString(output.resolve("Unexpected.class"), "extra");
        assertFalse(expectedClasses.recordedOutputsCurrent(projectDir, output, fingerprint));
        Files.delete(output.resolve("Unexpected.class"));
        Files.writeString(main, "tampered");
        assertFalse(expectedClasses.recordedOutputsCurrent(projectDir, output, fingerprint));
    }
}
