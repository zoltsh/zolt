package sh.zolt.cli.command.dependency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The final mutation command grammar (design §20): dependency scope is an explicit {@code --scope}
 * option, {@code versions} owns {@code [versions]}, {@code platforms} owns {@code [platforms]},
 * {@code bom versions}/{@code bom imports} own the two BOM maps, and every one of them edits source
 * in place rather than regenerating the manifest.
 */
final class ManifestMutationGrammarTest {
    @TempDir
    private Path tempDir;

    @Test
    void addWritesTheRequestedScopeAndLeavesEveryOtherByteAlone() throws IOException {
        Path project = project("add-scope", """
                # keep this banner

                [versions]
                junit = "5.13.4" # alias comment

                [dependencies]
                "com.example:lib" = "1.0.0" # pinned
                """);

        CommandResult result = run(project, "add", "org.junit.jupiter:junit-jupiter:5.13.4",
                "--scope", "test", "--no-resolve");

        assertEquals(0, result.exitCode(), result.stderr());
        String source = read(project);
        assertTrue(source.contains("# keep this banner"), source);
        assertTrue(source.contains("junit = \"5.13.4\" # alias comment"), source);
        assertTrue(source.contains("\"com.example:lib\" = \"1.0.0\" # pinned"), source);
        assertTrue(
                source.contains("[dependencies.test]\n\"org.junit.jupiter:junit-jupiter\" = \"5.13.4\""),
                source);
        assertTrue(result.stdout().contains("[dependencies.test]"), result.stdout());
    }

    /** An ordinary variant lives in exactly one lane, so an add moves it instead of duplicating it. */
    @Test
    void addMovesAnOrdinaryVariantBetweenLanesWithItsTrailingComment() throws IOException {
        Path project = project("add-move", """
                [dependencies]
                "com.example:lib" = "1.0.0" # reviewed
                """);

        CommandResult result = run(project, "add", "com.example:lib:1.0.0", "--scope", "api", "--no-resolve");

        assertEquals(0, result.exitCode(), result.stderr());
        String source = read(project);
        assertTrue(source.contains("[dependencies.api]\n\"com.example:lib\" = \"1.0.0\" # reviewed"), source);
        assertFalse(source.contains("[dependencies]\n\"com.example:lib\""), source);
        assertTrue(
                result.stdout().contains(
                        "Updated dependency com.example:lib from 1.0.0 in [dependencies] "
                                + "to 1.0.0 in [dependencies.api]"),
                result.stdout());
    }

    @Test
    void removeDeletesOnlyTheNamedScopedAssignment() throws IOException {
        Path project = project("remove-scope", """
                [dependencies]
                "com.example:lib" = "1.0.0"

                [dependencies.test]
                "org.junit.jupiter:junit-jupiter" = "5.13.4"
                """);

        CommandResult result = run(project, "remove", "org.junit.jupiter:junit-jupiter",
                "--scope", "test", "--no-resolve");

        assertEquals(0, result.exitCode(), result.stderr());
        String source = read(project);
        assertFalse(source.contains("junit-jupiter"), source);
        assertTrue(source.contains("\"com.example:lib\" = \"1.0.0\""), source);
        // An emptied table is retained so deliberate placement and comments survive (design §18.5).
        assertTrue(source.contains("[dependencies.test]"), source);
    }

    @Test
    void removeOfAnAbsentDependencyIsAnIdempotentNoOp() throws IOException {
        Path project = project("remove-absent", """
                [dependencies]
                "com.example:lib" = "1.0.0"
                """);
        String before = read(project);

        CommandResult result = run(project, "remove", "com.example:absent", "--scope", "test", "--no-resolve");

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals(before, read(project));
        assertTrue(result.stdout().contains("nothing to remove"), result.stdout());
    }

    @Test
    void versionsSetAndRemoveOwnTheAliasMap() throws IOException {
        Path project = project("versions", """
                [dependencies]
                "com.example:lib" = "1.0.0"
                """);

        assertEquals(0, run(project, "versions", "set", "junit", "5.13.4", "--no-resolve").exitCode());
        assertTrue(read(project).contains("[versions]\njunit = \"5.13.4\""), read(project));

        assertEquals(0, run(project, "versions", "set", "junit", "5.14.0", "--no-resolve").exitCode());
        assertTrue(read(project).contains("junit = \"5.14.0\""), read(project));

        assertEquals(0, run(project, "versions", "remove", "junit", "--no-resolve").exitCode());
        assertFalse(read(project).contains("junit = "), read(project));
    }

    @Test
    void versionsRemoveRefusesWhileAnAliasIsStillReferenced() throws IOException {
        Path project = project("versions-referenced", """
                [versions]
                junit = "5.13.4"

                [dependencies.test]
                "org.junit.jupiter:junit-jupiter" = { versionRef = "junit" }
                """);
        String before = read(project);

        CommandResult result = run(project, "versions", "remove", "junit", "--no-resolve");

        assertEquals(1, result.exitCode());
        assertTrue(
                (result.stdout() + result.stderr()).contains("[dependencies.test].org.junit.jupiter:junit-jupiter"),
                result.stdout() + result.stderr());
        assertEquals(before, read(project));
    }

    @Test
    void platformsSetAcceptsAFixedVersionOrOneVersionReference() throws IOException {
        Path project = project("platforms", """
                [versions]
                spring-boot = "3.4.1"

                [dependencies]
                "com.example:lib" = "1.0.0"
                """);

        assertEquals(0, run(project, "platforms", "set", "io.netty:netty-bom", "4.1.119.Final",
                "--no-resolve").exitCode());
        assertTrue(read(project).contains("\"io.netty:netty-bom\" = \"4.1.119.Final\""), read(project));

        assertEquals(0, run(project, "platforms", "set",
                "org.springframework.boot:spring-boot-dependencies",
                "--version-ref", "spring-boot", "--no-resolve").exitCode());
        assertTrue(
                read(project).contains(
                        "\"org.springframework.boot:spring-boot-dependencies\" = { versionRef = \"spring-boot\" }"),
                read(project));

        assertEquals(0, run(project, "platforms", "remove", "io.netty:netty-bom", "--no-resolve").exitCode());
        assertFalse(read(project).contains("netty-bom"), read(project));
    }

    @Test
    void platformsSetRejectsAVersionAndVersionRefTogether() throws IOException {
        Path project = project("platforms-conflict", """
                [versions]
                netty = "4.1.119.Final"

                [dependencies]
                "com.example:lib" = "1.0.0"
                """);
        String before = read(project);

        CommandResult result = run(project, "platforms", "set", "io.netty:netty-bom", "4.1.119.Final",
                "--version-ref", "netty", "--no-resolve");

        assertEquals(1, result.exitCode());
        assertEquals(before, read(project));
    }

    @Test
    void bomVersionsAndImportsOwnTheTwoBomMaps() throws IOException {
        Path project = bomProject("bom");

        assertEquals(0, run(project, "bom", "versions", "set", "org.postgresql:postgresql", "42.7.4",
                "--no-resolve").exitCode());
        assertTrue(read(project).contains("[bom.versions]\n\"org.postgresql:postgresql\" = \"42.7.4\""),
                read(project));

        assertEquals(0, run(project, "bom", "imports", "set", "com.fasterxml.jackson:jackson-bom",
                "--version-ref", "jackson", "--no-resolve").exitCode());
        assertTrue(
                read(project).contains(
                        "[bom.imports]\n\"com.fasterxml.jackson:jackson-bom\" = { versionRef = \"jackson\" }"),
                read(project));

        assertEquals(0, run(project, "bom", "versions", "remove", "org.postgresql:postgresql",
                "--no-resolve").exitCode());
        assertFalse(read(project).contains("postgresql"), read(project));
    }

    /** Only {@code bom versions set} takes artifact metadata; a BOM import's semantics are fixed. */
    @Test
    void bomVersionsSetCarriesClassifierAndTypeInCanonicalFieldOrder() throws IOException {
        Path project = bomProject("bom-variant");

        CommandResult result = run(project, "bom", "versions", "set", "org.postgresql:postgresql", "42.7.4",
                "--classifier", "tests", "--type", "test-jar", "--no-resolve");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(
                read(project).contains(
                        "\"org.postgresql:postgresql\" = { version = \"42.7.4\", classifier = \"tests\", type = \"test-jar\" }"),
                read(project));
    }

    @Test
    void bomImportsRejectClassifierAndType() throws IOException {
        Path project = bomProject("bom-import-flags");

        CommandResult result = run(project, "bom", "imports", "set", "com.fasterxml.jackson:jackson-bom",
                "2.19.0", "--classifier", "tests", "--no-resolve");

        assertEquals(2, result.exitCode());
    }

    @Test
    void noResolveNamesTheCommandThatRefreshesTheStaleLock() throws IOException {
        Path project = project("no-resolve", """
                [dependencies]
                "com.example:lib" = "1.0.0"
                """);

        CommandResult result = run(project, "add", "com.example:other:2.0.0", "--no-resolve");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("run zolt resolve to refresh zolt.lock"), result.stdout());
        assertFalse(Files.exists(project.resolve("zolt.lock")));
    }

    @Test
    void bareVersionReportsTheInstalledZoltVersionAndNeverMutates() throws IOException {
        Path project = project("bare-version", """
                [dependencies]
                "com.example:lib" = "1.0.0"
                """);
        String before = read(project);

        CommandResult result = execute("version");

        assertEquals(0, result.exitCode(), result.stderr());
        assertFalse(result.stdout().isBlank());
        assertEquals(before, read(project));
    }

    private CommandResult run(Path project, String... arguments) {
        String[] full = new String[arguments.length + 2];
        System.arraycopy(arguments, 0, full, 0, arguments.length);
        full[arguments.length] = "--cwd";
        full[arguments.length + 1] = project.toString();
        return execute(full);
    }

    private Path project(String name, String body) throws IOException {
        Path project = tempDir.resolve(name);
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = 21

                %s""".formatted(name, body));
        return project;
    }

    private Path bomProject(String name) throws IOException {
        Path project = tempDir.resolve(name);
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"

                [versions]
                jackson = "2.19.0"
                """.formatted(name));
        return project;
    }

    private static String read(Path project) throws IOException {
        return Files.readString(project.resolve("zolt.toml"));
    }
}
