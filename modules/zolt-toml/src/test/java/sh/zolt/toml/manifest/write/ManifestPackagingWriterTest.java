package sh.zolt.toml.manifest.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static sh.zolt.toml.manifest.ManifestSemanticTestSupport.decodeAuthoredManifest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredNativeImage;
import sh.zolt.manifest.authored.AuthoredPackage;
import sh.zolt.manifest.authored.AuthoredPackageManifest;
import sh.zolt.manifest.authored.AuthoredPackaging;
import sh.zolt.manifest.authored.AuthoredSpringBoot;

final class ManifestPackagingWriterTest {
    @Test
    void emitsPackageManifestFrameworkAndNativeSettingsInSchemaOrder() {
        AuthoredPackaging packaging = new AuthoredPackaging(
                Optional.of(new AuthoredPackage(
                        Optional.of(AuthoredPackage.Mode.UBER_JAR),
                        Optional.of(true),
                        Optional.of(true),
                        Optional.of(true),
                        Optional.of(AuthoredPackage.DuplicatePolicy.FIRST_WINS))),
                Optional.of(new AuthoredPackageManifest(Map.of(
                        "Main-Class", "com.example.Main",
                        "Automatic-Module-Name", "com.example.library"))),
                Optional.of(new AuthoredSpringBoot(Optional.of(true))),
                Optional.of(new AuthoredNativeImage(
                        Optional.of("example"),
                        Optional.of(new ManifestRelativePath("images/example")),
                        Optional.of(List.of("--no-fallback", "--native-image-info")))),
                Optional.empty());

        String output = write(packaging);

        assertEquals(
                """
                [package]
                mode = "uber-jar"
                sources = true
                javadoc = true
                testJar = true
                duplicates = "first-wins"

                [package.manifest]
                Automatic-Module-Name = "com.example.library"
                Main-Class = "com.example.Main"

                [framework.spring-boot]
                native = true

                [native]
                name = "example"
                output = "images/example"
                args = ["--no-fallback", "--native-image-info"]
                """,
                output);
        assertFalse(Toml.parse(output).hasErrors());
        assertEquals(packaging, decodePackaging(output));
    }

    @Test
    void emitsTheWorkspaceBomProjectionAndSelectorFormsExactly() {
        AuthoredBom bom = new AuthoredBom(
                Optional.of(new AuthoredBom.Members(
                        new AuthoredBom.AllMembers(),
                        List.of(new WorkspaceMemberPath("apps/admin")))),
                Optional.of(Map.of(
                        coordinate("org.example:fixed"),
                        new AuthoredBom.Version(
                                new PlatformSelector.FixedVersion("1.2.3"),
                                Optional.empty(),
                                Optional.empty()),
                        coordinate("org.example:referenced"),
                        new AuthoredBom.Version(
                                new PlatformSelector.VersionReference(id("release")),
                                Optional.of("tests"),
                                Optional.of("test-jar")))),
                Optional.of(Map.of(
                        coordinate("org.example:fixed-bom"),
                        new PlatformSelector.FixedVersion("4.0.0"),
                        coordinate("org.example:referenced-bom"),
                        new PlatformSelector.VersionReference(id("platform")))));
        AuthoredPackaging packaging = new AuthoredPackaging(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(bom));

        String output = write(packaging);

        assertEquals(
                """
                [bom]
                members = true
                exclude = ["apps/admin"]

                [bom.versions]
                "org.example:fixed" = "1.2.3"
                "org.example:referenced" = { versionRef = "release", classifier = "tests", type = "test-jar" }

                [bom.imports]
                "org.example:fixed-bom" = "4.0.0"
                "org.example:referenced-bom" = { versionRef = "platform" }
                """,
                output);
        assertFalse(output.contains("[package]"));
        assertFalse(output.contains("{ }"));
        assertEquals(packaging, decodePackaging(output));
    }

    @Test
    void emitsSpringBootServiceModeWithoutDefaultPackageNoise() {
        AuthoredPackaging packaging = new AuthoredPackaging(
                Optional.of(new AuthoredPackage(
                        Optional.of(AuthoredPackage.Mode.SPRING_BOOT),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty())),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        assertEquals(
                """
                [package]
                mode = "spring-boot"
                """,
                write(packaging));
    }

    @Test
    void omitsCanonicalDefaultsAndEmptyCollections() {
        AuthoredPackaging defaults = new AuthoredPackaging(
                Optional.of(new AuthoredPackage(
                        Optional.of(AuthoredPackage.Mode.JAR),
                        Optional.of(false),
                        Optional.of(false),
                        Optional.of(false),
                        Optional.empty())),
                Optional.of(new AuthoredPackageManifest(Map.of())),
                Optional.of(new AuthoredSpringBoot(Optional.of(false))),
                Optional.empty(),
                Optional.empty());
        AuthoredPackaging emptyBomCollections = new AuthoredPackaging(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new AuthoredBom(
                        Optional.empty(), Optional.of(Map.of()), Optional.of(Map.of()))));

        assertEquals("", write(AuthoredPackaging.empty()));
        assertEquals("", write(defaults));
        assertEquals("", write(emptyBomCollections));
    }

    private static String write(AuthoredPackaging packaging) {
        ManifestTomlEmitter emitter = new ManifestTomlEmitter();
        new ManifestPackagingWriter().write(emitter, packaging);
        return emitter.finish();
    }

    private static AuthoredPackaging decodePackaging(String source) {
        return decodeAuthoredManifest("[project]\nname = \"round-trip\"\n\n" + source)
                .packaging();
    }

    private static DependencyCoordinate coordinate(String value) {
        return new DependencyCoordinate(value);
    }

    private static LocalId id(String value) {
        return new LocalId(value);
    }
}
