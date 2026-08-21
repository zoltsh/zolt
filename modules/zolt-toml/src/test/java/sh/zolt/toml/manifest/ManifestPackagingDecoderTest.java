package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredPackage;
import sh.zolt.manifest.authored.AuthoredPackaging;
import sh.zolt.toml.ZoltConfigException;

final class ManifestPackagingDecoderTest {
    private final ManifestPackagingDecoder decoder = new ManifestPackagingDecoder();

    @Test
    void returnsTheEmptyAggregateForCompleteOmission() {
        assertEquals(AuthoredPackaging.empty(), decode(""));
    }

    @Test
    void composesPackageManifestSpringBootAndNativeWithoutDefaults() {
        AuthoredPackaging packaging = decode("""
                [native]
                args = ["--native-image-info", ""]

                [framework.spring-boot]
                native = false

                [package.manifest]
                Zeta = "last"
                Alpha = "first"

                [package]
                mode = "uber-jar"
                sources = false
                duplicates = "first-wins"
                """);

        AuthoredPackage settings = packaging.packageSettings().orElseThrow();
        assertEquals(AuthoredPackage.Mode.UBER_JAR, settings.mode().orElseThrow());
        assertEquals(Optional.of(false), settings.sources());
        assertTrue(settings.javadoc().isEmpty());
        assertTrue(settings.testJar().isEmpty());
        assertEquals(
                AuthoredPackage.DuplicatePolicy.FIRST_WINS,
                settings.duplicates().orElseThrow());
        assertEquals(
                List.of("Alpha", "Zeta"),
                List.copyOf(packaging.manifest().orElseThrow().attributes().keySet()));
        assertEquals(
                Optional.of(false),
                packaging.springBoot().orElseThrow().nativeImage());
        assertEquals(
                Optional.of(List.of("--native-image-info", "")),
                packaging.nativeImage().orElseThrow().args());
        assertTrue(packaging.bom().isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> packaging.manifest().orElseThrow().attributes().clear());
    }

    @Test
    void retainsBomCompatibleFalseAndExplicitEmptyPresence() {
        AuthoredPackaging packaging = decode("""
                [package]
                sources = false
                javadoc = false
                testJar = false

                [package.manifest]

                [bom.versions]
                [bom.imports]
                """);

        AuthoredPackage settings = packaging.packageSettings().orElseThrow();
        assertEquals(Optional.of(false), settings.sources());
        assertEquals(Optional.of(false), settings.javadoc());
        assertEquals(Optional.of(false), settings.testJar());
        assertEquals(Map.of(), packaging.manifest().orElseThrow().attributes());
        AuthoredBom bom = packaging.bom().orElseThrow();
        assertEquals(Optional.of(Map.of()), bom.versions());
        assertEquals(Optional.of(Map.of()), bom.imports());
        assertTrue(packaging.springBoot().isEmpty());
        assertTrue(packaging.nativeImage().isEmpty());
    }

    @Test
    void retainsCompleteBomCollectionsAfterPresenceValidation() {
        AuthoredBom bom = decode("""
                [bom.versions]
                "org.example:library" = "1.0"

                [bom.imports]
                "org.example:platform" = { versionRef = "platform" }
                """).bom().orElseThrow();

        assertEquals(
                List.of(new DependencyCoordinate("org.example:library")),
                List.copyOf(bom.versions().orElseThrow().keySet()));
        assertEquals(
                List.of(new DependencyCoordinate("org.example:platform")),
                List.copyOf(bom.imports().orElseThrow().keySet()));
    }

    @Test
    void anchorsPackageModeAndEachAttachedArtifactConflictToBomEvidence() {
        assertSemanticFailure(
                "package.mode = \"jar\"\n[bom]\nmembers = true\n",
                "`bom.members`",
                "A BOM cannot author package mode");
        for (String field : List.of("sources", "javadoc", "testJar")) {
            assertSemanticFailure(
                    "package." + field + " = true\n[bom.versions]\n",
                    "[bom.versions]",
                    "A BOM cannot enable sources, javadoc, or test JAR artifacts.");
        }
    }

    @Test
    void anchorsManifestConflictToBomAndPrioritizesEarlierPackageFields() {
        assertSemanticFailure(
                "[package.manifest]\nName = \"demo\"\n[bom.imports]\n",
                "[bom.imports]",
                "A BOM cannot author a JAR manifest.");

        ZoltConfigException failure = assertSemanticFailure(
                """
                package.mode = "jar"
                [package.manifest]
                Name = "demo"
                [bom]
                members = true
                """,
                "`bom.members`",
                "A BOM cannot author package mode");
        assertFalse(failure.getMessage().contains("JAR manifest"), failure.getMessage());
    }

    @Test
    void observesBomPresenceBeforeLaterBomLeafFailures() {
        ZoltConfigException members = assertSemanticFailure(
                """
                package.mode = "jar"
                [bom]
                members = true
                exclude = ["apps/api", "apps/api"]
                """,
                "`bom.members`",
                "A BOM cannot author package mode");
        assertFalse(members.getMessage().contains("bom.exclude"), members.getMessage());

        ZoltConfigException versions = assertSemanticFailure(
                """
                package.mode = "jar"
                [bom.versions]
                "org.example:demo" = "LATEST"
                """,
                "[bom.versions]",
                "A BOM cannot author package mode");
        assertFalse(versions.getMessage().contains("org.example:demo"), versions.getMessage());

        ZoltConfigException imports = assertSemanticFailure(
                """
                package.mode = "jar"
                [bom.imports]
                "org.example:demo-bom" = "LATEST"
                """,
                "[bom.imports]",
                "A BOM cannot author package mode");
        assertFalse(imports.getMessage().contains("org.example:demo-bom"), imports.getMessage());
    }

    @Test
    void stagesSpringBootBeforeNativeAndAnchorsNativeAtItsFirstField() {
        ZoltConfigException spring = assertSemanticFailure(
                """
                [native]
                args = []
                [framework.spring-boot]
                native = false
                [bom.versions]
                """,
                "`framework.spring-boot.native`",
                "A BOM cannot author Spring Boot framework settings.");
        assertFalse(spring.getMessage().contains("`native.args`"), spring.getMessage());

        assertSemanticFailure(
                """
                [native]
                args = ["--native-image-info"]
                output = "native"
                [bom.imports]
                """,
                "`native.output`",
                "A BOM cannot author native-image settings.");
    }

    @Test
    void preservesLeafFailuresBeforePresenceDependentConflicts() {
        ZoltConfigException members = assertSemanticFailure(
                "package.mode = \"jar\"\n[bom]\nmembers = false\n",
                "`bom.members`",
                "BOM members must be `true` or a nonempty array");
        assertFalse(members.getMessage().contains("A BOM cannot"), members.getMessage());

        ZoltConfigException nativeImage = assertSemanticFailure(
                "native.output = \"native\"\n[bom.versions]\n",
                "`native.output`",
                "must contain at least one nondefault field");
        assertFalse(nativeImage.getMessage().contains("A BOM cannot"), nativeImage.getMessage());
    }

    @Test
    void propagatesEarlierPackageFailuresBeforeLaterDomains() {
        assertSemanticFailure(
                """
                [native]
                name = "application"
                [framework.spring-boot]
                native = true
                [bom.versions]
                [package]
                duplicates = "first-wins"
                """,
                "`package.duplicates`",
                "Package duplicates are valid only with mode `uber-jar`.");
    }

    @Test
    void requiresANonNullDecodeIndex() {
        assertThrows(NullPointerException.class, () -> decoder.decode(null));
    }

    private AuthoredPackaging decode(String source) {
        return decoder.decode(ManifestSemanticTestSupport.index(source));
    }

    private ZoltConfigException assertSemanticFailure(
            String source,
            String path,
            String detail) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode(source));
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        return failure;
    }
}
