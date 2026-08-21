package sh.zolt.toml.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestSemanticTestSupport.decodeAuthoredManifest;
import static sh.zolt.toml.manifest.ManifestSemanticTestSupport.decodeAuthoredManifestWithNullIndex;

import org.junit.jupiter.api.Test;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.toml.ZoltConfigException;

final class ManifestAuthoredDecoderTest {
    @Test
    void rejectsMissingIdentityBeforeLaterSemanticDomains() {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeAuthoredManifest("""
                        [toolchain.zolt]
                        version = "latest"
                        [tasks.later]
                        run = [" "]
                        """));

        assertEquals(
                "Invalid authored manifest: An authored manifest must contain a [workspace] "
                        + "and/or [project] domain.",
                failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertFalse(failure.getMessage().contains("toolchain.zolt.version"));
        assertFalse(failure.getMessage().contains("tasks.later"));
    }

    @Test
    void composesEveryAuthoredComponentWithoutAddingDefaults() {
        AuthoredManifest manifest = decodeAuthoredManifest("""
                [tasks]
                [aliases]
                [publish.repositories]
                [package.manifest]
                Name = "demo"
                [test.suites]
                [generated.tools]
                [resources.tokens]
                [compiler]
                encoding = "UTF-8"
                [build]
                sources = ["src/main/java"]
                [dependencies.policy]
                conflicts = "resolve"
                [dependencies.constraints]
                [dependencies]
                [platforms]
                [credentials]
                [repositories.company]
                url = "https://repo.example.test/maven"
                [versions]
                [toolchain.zolt]
                version = "0.1.0-rc.1"
                [project]
                name = "demo"
                [workspace]
                name = "root"
                [workspace.members]
                include = ["modules/*"]
                """);

        assertTrue(manifest.workspace().isPresent());
        assertTrue(manifest.project().isPresent());
        assertTrue(manifest.toolchains().zolt().isPresent());
        assertTrue(manifest.toolchains().mainJava().isEmpty());
        assertTrue(manifest.versions().isPresent());
        assertEquals(1, manifest.repositories().orElseThrow().named().size());
        assertTrue(manifest.credentials().isPresent());
        assertTrue(manifest.platforms().isPresent());
        assertTrue(manifest.dependencies().isPresent());
        assertTrue(manifest.dependencyConstraints().isPresent());
        assertTrue(manifest.dependencyPolicy().isPresent());
        assertTrue(manifest.build().build().isPresent());
        assertTrue(manifest.build().compiler().isPresent());
        assertTrue(manifest.build().resources().isPresent());
        assertTrue(manifest.build().tests().isPresent());
        assertTrue(manifest.build().coverage().isEmpty());
        assertTrue(manifest.generated().isPresent());
        assertTrue(manifest.packaging().packageSettings().isEmpty());
        assertTrue(manifest.packaging().manifest().isPresent());
        assertTrue(manifest.publishing().isPresent());
        assertTrue(manifest.publishing().orElseThrow().repositories().isEmpty());
        assertTrue(manifest.commands().isPresent());
        assertTrue(manifest.commands().orElseThrow().tasks().isEmpty());
        assertTrue(manifest.commands().orElseThrow().aliases().isEmpty());
    }

    @Test
    void reportsVirtualRootDomainsInCanonicalOrderRegardlessOfSourceOrder() {
        ZoltConfigException failure = assertFailure("""
                [publish.repositories]
                [package.manifest]
                [test.suites]
                [generated.tools]
                [compiler]
                encoding = "UTF-8"
                [build]
                sources = ["src/main/java"]
                [dependencies]
                [workspace]
                name = "root"
                [workspace.members]
                include = ["modules/*"]
                """, "[dependencies]", "project-only dependencies");

        assertFalse(failure.getMessage().contains("build.sources"));
        assertFalse(failure.getMessage().contains("package.manifest"));
        assertFalse(failure.getMessage().contains("publish.repositories"));
    }

    @Test
    void retainsEachVirtualRootObserverAnchorBeforeLaterCommandFailures() {
        assertVirtualFailure("""
                [workspace]
                name = "root"
                [workspace.members]
                include = ["modules/*"]
                [compiler]
                encoding = "UTF-8"
                [tasks.later]
                run = [" "]
                """, "`compiler.encoding`", "compiler settings");
        assertVirtualFailure("""
                [workspace]
                name = "root"
                [workspace.members]
                include = ["modules/*"]
                [generated.tools]
                [tasks.later]
                run = [" "]
                """, "[generated.tools]", "generated sources");
        assertVirtualFailure("""
                [workspace]
                name = "root"
                [workspace.members]
                include = ["modules/*"]
                [package]
                mode = "jar"
                [tasks.later]
                run = [" "]
                """, "`package.mode`", "packaging");
        assertVirtualFailure("""
                [workspace]
                name = "root"
                [workspace.members]
                include = ["modules/*"]
                [publish.repositories]
                [tasks.later]
                run = [" "]
                """, "[publish.repositories]", "publishing");
    }

    @Test
    void reportsEarlierProjectBomConflictsBeforePackagingAndLaterBomFailures() {
        ZoltConfigException failure = assertFailure("""
                [project]
                name = "catalog"
                java = 21
                [dependencies]
                [package]
                mode = "jar"
                [bom]
                members = true
                exclude = ["apps/api", "apps/api"]
                [tasks.later]
                run = [" "]
                """, "`bom.members`", "A BOM cannot author project.java.");

        assertFalse(failure.getMessage().contains("package mode"));
        assertFalse(failure.getMessage().contains("bom.exclude"));
        assertFalse(failure.getMessage().contains("tasks.later"));
    }

    @Test
    void requiresANonNullDecodeIndex() {
        assertEquals(
                "Manifest decode index is required.",
                assertThrows(
                                NullPointerException.class,
                                () -> decodeAuthoredManifestWithNullIndex())
                        .getMessage());
    }

    private static void assertVirtualFailure(String source, String path, String domain) {
        ZoltConfigException failure = assertFailure(
                source, path, "A virtual workspace root cannot author project-only " + domain + ".");
        assertFalse(failure.getMessage().contains("tasks.later"), failure.getMessage());
    }

    private static ZoltConfigException assertFailure(
            String source,
            String path,
            String detail) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decodeAuthoredManifest(source));
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        return failure;
    }
}
