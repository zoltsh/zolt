package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class AuthoredDependencyMetadataTest {
    @Test
    void retainsExternalMetadataWithoutNormalizingNativeSpelling() {
        ArrayList<DependencyCoordinate> exclusions =
                new ArrayList<>(List.of(new DependencyCoordinate("Commons.Logging:Legacy-Api")));
        AuthoredDependencyMetadata metadata = new AuthoredDependencyMetadata(
                true,
                false,
                Optional.of("Linux-X86_64"),
                Optional.of("test-jar"),
                exclusions);
        exclusions.clear();

        assertEquals("Linux-X86_64", metadata.classifier().orElseThrow());
        assertEquals("test-jar", metadata.type().orElseThrow());
        assertEquals("Commons.Logging:Legacy-Api", metadata.exclusions().getFirst().value());
        assertThrows(
                UnsupportedOperationException.class,
                () -> metadata.exclusions().add(new DependencyCoordinate("a:b")));
    }

    @Test
    void rejectsBlankUnsafeOrAmbiguousVariantValues() {
        assertThrows(IllegalArgumentException.class, () -> metadata(Optional.of(" "), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> metadata(Optional.empty(), Optional.of("a/b")));
        assertThrows(IllegalArgumentException.class, () -> metadata(Optional.of("linux|x86"), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> metadata(Optional.empty(), Optional.of("jar|tests")));
    }

    @Test
    void rejectsNullContainersAndExclusionEntries() {
        assertThrows(
                NullPointerException.class,
                () -> new AuthoredDependencyMetadata(false, false, null, Optional.empty(), List.of()));
        assertThrows(
                NullPointerException.class,
                () -> new AuthoredDependencyMetadata(false, false, Optional.empty(), null, List.of()));
        assertThrows(
                NullPointerException.class,
                () -> new AuthoredDependencyMetadata(false, false, Optional.empty(), Optional.empty(), null));
        assertThrows(
                NullPointerException.class,
                () -> new AuthoredDependencyMetadata(
                        false, false, Optional.empty(), Optional.empty(), java.util.Arrays.asList((DependencyCoordinate) null)));
    }

    private static AuthoredDependencyMetadata metadata(Optional<String> classifier, Optional<String> type) {
        return new AuthoredDependencyMetadata(false, false, classifier, type, List.of());
    }
}
