package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class SourceRootLanguageTest {
    @Test
    void explicitMainAdmissionAllowsOnlyKotlinAmongRestrictedRoots() {
        ManifestRelativePath kotlin = new ManifestRelativePath("src/main/kotlin");

        assertEquals(kotlin, SourceRootLanguage.requireMainSupported(kotlin));
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceRootLanguage.requireMainSupported(
                        new ManifestRelativePath("src/main/scala")));
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceRootLanguage.requireMainSupported(
                        new ManifestRelativePath("src/android/kotlin")));
    }

    @Test
    void generalAdmissionAndMigrationRecognitionStillRejectKotlin() {
        ManifestRelativePath kotlin = new ManifestRelativePath("src/test/kotlin");

        assertEquals(
                Optional.of(SourceRootLanguage.KOTLIN),
                SourceRootLanguage.unsupported(kotlin.value()));
        assertThrows(
                IllegalArgumentException.class,
                () -> SourceRootLanguage.requireSupported(kotlin));
    }
}
