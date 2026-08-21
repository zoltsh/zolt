package sh.zolt.cli.quality;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CheckPackageMetadataCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void checkPackageAndManifestMetadataPassForLibraryProject() throws IOException {
        Path projectDir = tempDir.resolve("check-library-metadata");
        Files.createDirectories(projectDir.resolve("src/main/java/com/example"));
        Files.createDirectories(projectDir.resolve("src/test/java/com/example"));
        Files.writeString(projectDir.resolve("src/main/java/com/example/Library.java"), """
                package com.example;
                public final class Library {}
                """);
        Files.writeString(projectDir.resolve("src/test/java/com/example/LibraryTest.java"), """
                package com.example;
                public final class LibraryTest {}
                """);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-library-metadata")
                + libraryPackageConfig("Check Library Metadata", "com.example.check.library", true));

        CommandResult result = execute(
                "check",
                "--cwd", projectDir.toString(),
                "--check", "package-metadata",
                "--check", "manifest-metadata");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok package-metadata check-library-metadata Library package metadata is complete."));
        assertTrue(result.stdout().contains("ok manifest-metadata check-library-metadata Library manifest metadata is deterministic."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkPackageMetadataReportsMissingLibraryArtifactSetting() throws IOException {
        Path projectDir = tempDir.resolve("check-library-missing-tests");
        Files.createDirectories(projectDir.resolve("src/main/java/com/example"));
        Files.createDirectories(projectDir.resolve("src/test/java/com/example"));
        Files.writeString(projectDir.resolve("src/main/java/com/example/Library.java"), """
                package com.example;
                public final class Library {}
                """);
        Files.writeString(projectDir.resolve("src/test/java/com/example/LibraryTest.java"), """
                package com.example;
                public final class LibraryTest {}
                """);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-library-missing-tests")
                + libraryPackageConfig("Check Library Missing Tests", "com.example.check.missing.tests", false));

        CommandResult result = execute("check", "--cwd", projectDir.toString(), "--check", "package-metadata");

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error package-metadata [package].tests Test sources are present"));
        assertTrue(result.stdout().contains("next: Set [package].tests = true"));
        assertEquals("", result.stderr());
    }

    @Test
    void checkManifestMetadataReportsMissingAutomaticModuleName() throws IOException {
        Path projectDir = tempDir.resolve("check-library-missing-module");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-library-missing-module") + """

                [package]
                mode = "jar"
                sources = true
                javadoc = true

                [project.scm]
                url = "https://example.com/check-library-missing-module.git"
                """);

        CommandResult result = execute("check", "--cwd", projectDir.toString(), "--check", "manifest-metadata");

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error manifest-metadata [package.manifest].Automatic-Module-Name"));
        assertTrue(result.stdout().contains("next: Add [package.manifest].\"Automatic-Module-Name\""));
        assertEquals("", result.stderr());
    }

    @Test
    void checkManifestMetadataReportsZoltOwnedAttributes() throws IOException {
        Path projectDir = tempDir.resolve("check-library-owned-manifest");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("check-library-owned-manifest")
                + libraryPackageConfig("Check Library Owned Manifest", "com.example.check.owned", false)
                + """
                "Main-Class" = "com.example.Main"
                """);

        CommandResult result = execute("check", "--cwd", projectDir.toString(), "--check", "manifest-metadata");

        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("error manifest-metadata [package.manifest].Main-Class Manifest attribute `Main-Class` is owned by Zolt."));
        assertTrue(result.stdout().contains("next: Remove it from [package.manifest]; use [project].main for Main-Class."));
        assertEquals("", result.stderr());
    }

    @Test
    void checkWorkspacePackageAndManifestMetadataIdentifyMemberPaths() throws IOException {
        Path workspaceDir = tempDir.resolve("check-workspace-library-metadata");
        Path apiDir = workspaceDir.resolve("modules/api");
        Path implDir = workspaceDir.resolve("modules/impl");
        Files.createDirectories(apiDir);
        Files.createDirectories(implDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "check-workspace-library-metadata"

                [workspace.members]
                include = ["modules/api", "modules/impl"]
                """);
        Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api")
                + libraryPackageConfig("API Library", "com.example.api", false));
        Files.writeString(implDir.resolve("zolt.toml"), memberConfig("impl")
                + libraryPackageConfig("Implementation Library", "com.example.impl", false));

        CommandResult result = execute(
                "check",
                "--workspace",
                "--member", "modules/impl",
                "--cwd", workspaceDir.toString(),
                "--check", "package-metadata",
                "--check", "manifest-metadata");

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("ok package-metadata modules/impl impl Library package metadata is complete."));
        assertTrue(result.stdout().contains("ok manifest-metadata modules/impl impl Library manifest metadata is deterministic."));
        assertEquals("", result.stderr());
    }

    private static String libraryPackageConfig(String displayName, String moduleName, boolean tests) {
        String testsSetting = tests ? "testJar = true\n" : "";
        return """
                description = "Fixture library metadata for zolt check."
                url = "https://example.com/%s"
                issues = "https://example.com/%s/issues"
                license = "Apache-2.0"

                [project.scm]
                url = "https://example.com/%s.git"

                [project.developers.zolt]
                name = "%s"
                email = "team@example.com"

                [package]
                sources = true
                javadoc = true
                %s
                [package.manifest]
                "Automatic-Module-Name" = "%s"
                """.formatted(
                moduleName,
                moduleName,
                moduleName,
                displayName,
                testsSetting,
                moduleName);
    }
}
