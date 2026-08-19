package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class AuthoredFrameworkNativeTest {
    @Test
    void preservesSpringBootNativeFieldPresenceIncludingFalse() {
        AuthoredSpringBoot springBoot = new AuthoredSpringBoot(Optional.of(false));

        assertFalse(springBoot.nativeImage().orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredSpringBoot(Optional.empty()));
    }

    @Test
    void preservesNativeValuesAndArgumentOrderWithoutToolSpecificValidation() {
        ArrayList<String> arguments = new ArrayList<>(List.of("--no-fallback", "", "--native-image-info"));
        AuthoredNativeImage nativeImage = new AuthoredNativeImage(
                Optional.of("Images/My Native Binary"),
                Optional.of(new ManifestRelativePath("images/native")),
                Optional.of(arguments));
        arguments.clear();

        assertEquals("Images/My Native Binary", nativeImage.name().orElseThrow());
        assertEquals("images/native", nativeImage.output().orElseThrow().value());
        assertEquals(List.of("--no-fallback", "", "--native-image-info"), nativeImage.args().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> nativeImage.args().orElseThrow().clear());
    }

    @Test
    void rejectsNativeTablesWithoutANondefaultField() {
        assertThrows(
                IllegalArgumentException.class,
                () -> nativeImage(Optional.empty(), Optional.empty(), Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> nativeImage(
                        Optional.empty(),
                        Optional.of(new ManifestRelativePath("native")),
                        Optional.empty()));
        assertThrows(
                IllegalArgumentException.class,
                () -> nativeImage(Optional.empty(), Optional.empty(), Optional.of(List.of())));
        assertThrows(
                IllegalArgumentException.class,
                () -> nativeImage(Optional.of(" "), Optional.empty(), Optional.empty()));
    }

    @Test
    void retainsExplicitEmptyArgsWhenAnotherNativeFieldIsNondefault() {
        AuthoredNativeImage nativeImage = nativeImage(
                Optional.of("application"),
                Optional.empty(),
                Optional.of(List.of()));

        assertEquals(Optional.of(List.of()), nativeImage.args());
    }

    @Test
    void enforcesBomOwnedCrossDomainRestrictions() {
        AuthoredBom bom = new AuthoredBom(
                Optional.empty(), Optional.of(Map.of()), Optional.empty());
        AuthoredPackage withMode = new AuthoredPackage(
                Optional.of(AuthoredPackage.Mode.JAR),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        AuthoredNativeImage nativeImage = nativeImage(
                Optional.of("application"), Optional.empty(), Optional.empty());

        AuthoredPackaging retainedEmptyManifest = new AuthoredPackaging(
                Optional.empty(),
                Optional.of(new AuthoredPackageManifest(Map.of())),
                Optional.empty(),
                Optional.empty(),
                Optional.of(bom));

        assertEquals(Map.of(), retainedEmptyManifest.manifest().orElseThrow().attributes());
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredPackaging(
                        Optional.empty(),
                        Optional.of(new AuthoredPackageManifest(Map.of("X-Project", "example"))),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(bom)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredPackaging(
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(nativeImage),
                        Optional.of(bom)));
        for (boolean nativeEnabled : List.of(false, true)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AuthoredPackaging(
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of(new AuthoredSpringBoot(Optional.of(nativeEnabled))),
                            Optional.empty(),
                            Optional.of(bom)));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredPackaging(
                        Optional.of(withMode),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(bom)));

        for (int field = 0; field < 3; field++) {
            Optional<Boolean> sources = field == 0 ? Optional.of(true) : Optional.empty();
            Optional<Boolean> javadoc = field == 1 ? Optional.of(true) : Optional.empty();
            Optional<Boolean> testJar = field == 2 ? Optional.of(true) : Optional.empty();
            AuthoredPackage attachedArtifact = new AuthoredPackage(
                    Optional.empty(), sources, javadoc, testJar, Optional.empty());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new AuthoredPackaging(
                            Optional.of(attachedArtifact),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of(bom)));
        }

        AuthoredPackage explicitFalseAttachments = new AuthoredPackage(
                Optional.empty(), Optional.of(false), Optional.of(false), Optional.of(false), Optional.empty());
        assertEquals(
                explicitFalseAttachments,
                new AuthoredPackaging(
                                Optional.of(explicitFalseAttachments),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.of(bom))
                        .packageSettings().orElseThrow());
        assertEquals(AuthoredPackaging.empty(), AuthoredPackaging.empty());
    }

    private static AuthoredNativeImage nativeImage(
            Optional<String> name,
            Optional<ManifestRelativePath> output,
            Optional<List<String>> args) {
        return new AuthoredNativeImage(name, output, args);
    }
}
