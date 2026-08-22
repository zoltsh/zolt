package sh.zolt.toml.manifest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.project.CoverageSettings;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltConfigException;

/**
 * Final-language-only behavior of the project boundary: golden fixtures, coverage floors, and the
 * plain diagnostics that reject legacy spellings.
 */
final class ManifestFinalBoundaryTest {
    @Test
    void everyStandaloneGoldenLoadsThroughTheFinalBoundary() throws IOException {
        assertEquals(
                "hello", FinalManifests.golden("standalone-application.toml").project().name());
        ProjectConfig library = FinalManifests.golden("library-api-boundary.toml");
        assertEquals("2.0.17", library.apiDependencies().get("org.slf4j:slf4j-api"));
        assertEquals(
                "2.19.0", library.dependencies().get("com.fasterxml.jackson.core:jackson-databind"));
        ProjectConfig springBoot = FinalManifests.golden("spring-boot-service.toml");
        assertEquals(PackageMode.SPRING_BOOT, springBoot.packageSettings().mode());
        assertEquals(
                "4.0.6",
                springBoot.platforms().get("org.springframework.boot:spring-boot-dependencies"));
        assertTrue(springBoot.managedDependencies()
                .contains("org.springframework.boot:spring-boot-starter-webmvc"));
        ProjectConfig central = FinalManifests.golden("central-ready-library.toml");
        assertEquals("Apache-2.0", central.packageSettings().metadata().license());
        assertEquals(
                "https://github.com/example/library",
                central.packageSettings().metadata().scm());
        assertTrue(central.packageSettings().sources());
        ProjectConfig enterprise = FinalManifests.golden("enterprise-repository.toml");
        assertEquals(
                "https://repo.example.com/maven", enterprise.repositories().get("company"));
        assertEquals(
                "MAVEN_USERNAME",
                enterprise.repositoryCredentials().get("company").usernameEnv().orElseThrow());
    }

    @Test
    void coverageFloorsReachTheCoverageSettings() {
        CoverageSettings adapted = FinalManifests.loader().coverageFloors(
                """
                [project]
                name = "covered"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [coverage]
                line = 88
                branch = 74
                instruction = 80
                method = 85
                """);

        assertEquals(Optional.of(88.0), adapted.minLine());
        assertEquals(Optional.of(74.0), adapted.minBranch());
        assertEquals(Optional.of(80.0), adapted.minInstruction());
        assertEquals(Optional.of(85.0), adapted.minMethod());
    }

    @Test
    void virtualWorkspaceRootCoverageFloorsLoadWithoutAProjectDomain() throws IOException {
        CoverageSettings floors = FinalManifests.loader()
                .coverageFloors(FinalManifests.goldenSource("virtual-workspace.toml"));

        assertEquals(Optional.of(88.0), floors.minLine());
        assertEquals(Optional.of(74.0), floors.minBranch());
        assertEquals(Optional.empty(), floors.minInstruction());
        assertEquals(Optional.empty(), floors.minMethod());
    }

    @Test
    void absentCoverageSectionHasNoFloors() {
        assertEquals(
                CoverageSettings.none(),
                FinalManifests.loader().coverageFloors(
                        """
                        [project]
                        name = "uncovered"
                        version = "1.0.0"
                        group = "com.example"
                        java = 21
                        """));
    }

    @Test
    void removedSpellingsAreRejectedWithoutCompatibilityHints() {
        assertPlainRejection(
                """
                [project]
                name = "removed"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [%s.dependencies]
                "org.slf4j:slf4j-api" = "2.0.17"
                """.formatted("api"));
        assertPlainRejection(
                """
                [project]
                name = "removed"
                version = "1.0.0"
                group = "com.example"
                java = "21"
                """);
        assertPlainRejection(
                """
                [project]
                name = "removed"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [package]
                mode = "thin"
                """);
    }

    @Test
    void workspaceSelectorsAreRejectedInAStandaloneManifest() {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> FinalManifests.loader().load(
                        """
                        [project]
                        name = "standalone"
                        version = "1.0.0"
                        group = "com.example"
                        java = 21

                        [dependencies]
                        "com.example:core" = { workspace = true }
                        """));
        assertTrue(
                failure.getMessage().contains("workspace"),
                () -> "expected a workspace diagnostic, got: " + failure.getMessage());
    }

    private void assertPlainRejection(String finalSource) {
        ZoltConfigException failure =
                assertThrows(
                        ZoltConfigException.class,
                        () -> FinalManifests.loader().load(finalSource));
        String message = failure.getMessage();
        assertFalse(
                message.contains("legacy") || message.contains("migrat") || message.contains("rename"),
                () -> "design §21 Phase 2 forbids compatibility hints, got: " + message);
    }
}
