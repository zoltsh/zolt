package sh.zolt.quality.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.project.ProjectConfig;
import sh.zolt.quality.QualityCheckResult;
import sh.zolt.quality.QualityCheckService;
import sh.zolt.quality.QualityCheckStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageMetadataQualityCheckTest extends PackageQualityCheckTestSupport {
    @TempDir
    private Path tempDir;

    @Test
    void metadataPassesWhenNoLibraryPackageProfileIsRequested() throws IOException {
        Path projectDir = tempDir.resolve("plain-app");
        ProjectConfig config = parsePlainProject(projectDir, "");

        QualityCheckResult result = check.checkMetadata(Optional.empty(), projectDir, config);

        assertResult(
                result,
                QualityCheckService.PACKAGE_METADATA,
                QualityCheckStatus.PASSED,
                "plain-app",
                "No library package metadata is requested.",
                "");
    }

    @Test
    void metadataRequiresSourcesJarWhenPublicationMetadataIsEnabled() throws IOException {
        Path projectDir = tempDir.resolve("missing-sources");
        ProjectConfig config = parseProject(projectDir, "");

        QualityCheckResult result = check.checkMetadata(Optional.empty(), projectDir, config);

        assertResult(
                result,
                QualityCheckService.PACKAGE_METADATA,
                QualityCheckStatus.FAILED,
                "[package].sources",
                "Library package metadata is enabled, but sources jar generation is disabled.",
                "Set [package].sources = true for library projects.");
    }

    @Test
    void metadataRequiresJavadocWhenMainSourcesExist() throws IOException {
        Path projectDir = tempDir.resolve("missing-javadoc");
        Files.createDirectories(projectDir.resolve("src/main/java/com/example"));
        Files.writeString(projectDir.resolve("src/main/java/com/example/Api.java"), "package com.example; public interface Api {}\n");
        ProjectConfig config = parseProject(projectDir, """

                [package]
                sources = true
                """);

        QualityCheckResult result = check.checkMetadata(Optional.empty(), projectDir, config);

        assertResult(
                result,
                QualityCheckService.PACKAGE_METADATA,
                QualityCheckStatus.FAILED,
                "[package].javadoc",
                "Library package metadata is enabled, but javadoc jar generation is disabled.",
                "Set [package].javadoc = true when publishing Java APIs.");
    }

    @Test
    void metadataRequiresTestsJarWhenTestSourcesExist() throws IOException {
        Path projectDir = tempDir.resolve("missing-tests");
        Files.createDirectories(projectDir.resolve("src/test/java/com/example"));
        Files.writeString(projectDir.resolve("src/test/java/com/example/ApiTest.java"), "package com.example; final class ApiTest {}\n");
        ProjectConfig config = parseProject(projectDir, """

                [package]
                sources = true
                javadoc = true
                """);

        QualityCheckResult result = check.checkMetadata(Optional.empty(), projectDir, config);

        assertResult(
                result,
                QualityCheckService.PACKAGE_METADATA,
                QualityCheckStatus.FAILED,
                "[package].testJar",
                "Test sources are present, but tests jar generation is disabled for this library package.",
                "Set [package].testJar = true or remove test sources from the library artifact story.");
    }

    @Test
    void metadataReportsFirstMissingPublicationField() throws IOException {
        Path projectDir = tempDir.resolve("missing-publication-field");
        ProjectConfig config = parseProject(projectDir, 0, """

                [package]
                sources = true
                javadoc = true
                testJar = true
                """);

        QualityCheckResult result = check.checkMetadata(Optional.empty(), projectDir, config);

        assertResult(
                result,
                QualityCheckService.PACKAGE_METADATA,
                QualityCheckStatus.FAILED,
                publicationSubject(0),
                "Library package metadata is enabled, but publication metadata `"
                        + publicationValue(0) + "` is missing.",
                "Fill " + publicationSubject(0) + " in zolt.toml.");
    }

    @Test
    void metadataReportsLaterMissingPublicationFieldsWithTargetedNextSteps() throws IOException {
        for (int level = 1; level < PUBLICATION_LEVELS; level++) {
            Path projectDir = tempDir.resolve("missing-level-" + level);
            ProjectConfig config = parseProject(projectDir, level, """

                    [package]
                    sources = true
                    javadoc = true
                    testJar = true
                    """);

            QualityCheckResult result = check.checkMetadata(Optional.empty(), projectDir, config);

            assertResult(
                    result,
                    QualityCheckService.PACKAGE_METADATA,
                    QualityCheckStatus.FAILED,
                    publicationSubject(level),
                    "Library package metadata is enabled, but publication metadata `"
                            + publicationValue(level) + "` is missing.",
                    "Fill " + publicationSubject(level) + " in zolt.toml.");
        }
    }

    @Test
    void publicationMetadataAloneRequestsLibraryPackageProfile() throws IOException {
        // Any single publication value marks a library, so the missing sources jar is reported first.
        for (int level = 1; level <= PUBLICATION_LEVELS; level++) {
            Path projectDir = tempDir.resolve("metadata-profile-" + level);
            ProjectConfig config = parseProject(projectDir, level, "");

            QualityCheckResult result = check.checkMetadata(Optional.empty(), projectDir, config);

            assertResult(
                    result,
                    QualityCheckService.PACKAGE_METADATA,
                    QualityCheckStatus.FAILED,
                    "[package].sources",
                    "Library package metadata is enabled, but sources jar generation is disabled.",
                    "Set [package].sources = true for library projects.");
        }
    }

    @Test
    void metadataPassesWhenLibraryMetadataIsComplete() throws IOException {
        Path projectDir = tempDir.resolve("complete-metadata");
        ProjectConfig config = parseProject(projectDir, """

                [package]
                sources = true
                javadoc = true
                testJar = true
                """);

        QualityCheckResult result = check.checkMetadata(Optional.of("modules/library"), projectDir, config);

        assertEquals(Optional.of("modules/library"), result.member());
        assertResult(
                result,
                QualityCheckService.PACKAGE_METADATA,
                QualityCheckStatus.PASSED,
                "complete-metadata",
                "Library package metadata is complete.",
                "");
    }

    @Test
    void manifestMetadataPassesWhenNoLibraryProfileIsRequested() throws IOException {
        ProjectConfig config = parsePlainProject(tempDir.resolve("manifest-plain-app"), "");

        QualityCheckResult result = check.checkManifestMetadata(Optional.empty(), config);

        assertResult(
                result,
                QualityCheckService.MANIFEST_METADATA,
                QualityCheckStatus.PASSED,
                "manifest-plain-app",
                "No library manifest metadata is requested.",
                "");
    }

    @Test
    void manifestMetadataRejectsZoltOwnedAttributesCaseInsensitively() throws IOException {
        ProjectConfig config = parseProject(tempDir.resolve("owned-manifest"), """

                [package.manifest]
                "main-class" = "com.example.Main"
                """);

        QualityCheckResult result = check.checkManifestMetadata(Optional.of("modules/api"), config);

        assertEquals(Optional.of("modules/api"), result.member());
        assertResult(
                result,
                QualityCheckService.MANIFEST_METADATA,
                QualityCheckStatus.FAILED,
                "[package.manifest].main-class",
                "Manifest attribute `main-class` is owned by Zolt.",
                "Remove it from [package.manifest]; use [project].main for Main-Class.");
    }

    @Test
    void manifestMetadataRequiresAutomaticModuleNameForLibraryProfiles() throws IOException {
        ProjectConfig config = parseProject(tempDir.resolve("missing-module-name"), """

                [package]
                sources = true
                """);

        QualityCheckResult result = check.checkManifestMetadata(Optional.empty(), config);

        assertResult(
                result,
                QualityCheckService.MANIFEST_METADATA,
                QualityCheckStatus.FAILED,
                "[package.manifest].Automatic-Module-Name",
                "Library package metadata is enabled, but Automatic-Module-Name is missing.",
                "Add [package.manifest].\"Automatic-Module-Name\" with a stable Java module name.");
    }

    @Test
    void manifestMetadataPassesWithCaseInsensitiveAutomaticModuleName() throws IOException {
        ProjectConfig config = parseProject(tempDir.resolve("manifest-ok"), """

                [package]
                sources = true

                [package.manifest]
                "automatic-module-name" = "com.example.library"
                """);

        QualityCheckResult result = check.checkManifestMetadata(Optional.empty(), config);

        assertResult(
                result,
                QualityCheckService.MANIFEST_METADATA,
                QualityCheckStatus.PASSED,
                "manifest-ok",
                "Library manifest metadata is deterministic.",
                "");
    }
}
