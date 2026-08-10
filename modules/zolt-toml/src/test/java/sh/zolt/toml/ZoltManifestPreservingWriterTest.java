package sh.zolt.toml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.DependencySection;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ZoltManifestPreservingWriterTest {
    private final ZoltTomlParser parser = new ZoltTomlParser();
    private final ZoltTomlWriter writer = new ZoltTomlWriter();

    @TempDir
    private Path tempDir;

    @Test
    void dependencyEditPreservesEveryUnmodeledManifestDomainAndComment() throws IOException {
        String original = richManifest("""
                [dependencies] # dependency table stays here
                "com.example:existing"    = '1.0.0'    # keep this pin note
                """);
        Path manifest = write(original);
        ZoltManifestDocument document = parser.parseDocument(manifest);
        ProjectConfig updated = writer.addDependency(
                document.config(),
                DependencySection.MAIN,
                "com.example:added",
                "2.0.0");

        writer.writePreserving(manifest, document, updated);

        String actual = Files.readString(manifest);
        assertTrue(actual.contains("\"com.example:added\" = \"2.0.0\"\n"));
        assertTrue(actual.contains("\"com.example:existing\"    = '1.0.0'    # keep this pin note"));
        assertSentinelDomainsUnchanged(original, actual);
    }

    @Test
    void changingAnExistingValueTouchesOnlyItsValueSpan() throws IOException {
        String original = richManifest("""
                [dependencies]
                'com.example:lib'   =   '1.0.0'    # chosen deliberately
                "com.example:other" = "9.0.0"
                """);
        Path manifest = write(original);
        ZoltManifestDocument document = parser.parseDocument(manifest);
        ProjectConfig updated = writer.addDependency(
                document.config(),
                DependencySection.MAIN,
                "com.example:lib",
                "1.1.0");

        writer.writePreserving(manifest, document, updated);

        String expected = original.replace(
                "'com.example:lib'   =   '1.0.0'    # chosen deliberately",
                "'com.example:lib'   =   \"1.1.0\"    # chosen deliberately");
        assertEquals(expected, Files.readString(manifest));
    }

    @Test
    void longFormDependencySyntaxIsRejectedWithCanonicalRewriteGuidance() throws IOException {
        String original = richManifest("""
                [dependencies.\"com.example:lib\"]
                version = "1.0.0"
                optional = true
                """);
        Path manifest = write(original);
        ZoltManifestDocument document = parser.parseDocument(manifest);
        ProjectConfig updated = writer.addDependency(
                document.config(),
                DependencySection.MAIN,
                "com.example:lib",
                "1.1.0");

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> writer.writePreserving(manifest, document, updated));

        assertTrue(failure.getMessage().contains("long-form dependency table"), failure.getMessage());
        assertTrue(failure.getMessage().contains(
                "\"com.example:lib\" = { version = \"1.0.0\", optional = true }"),
                failure.getMessage());
        assertEquals(original, Files.readString(manifest));
    }

    @Test
    void removingLongFormDependencyIsRejectedWithoutChangingTheManifest() throws IOException {
        String original = richManifest("""
                [dependencies.\"com.example:lib\"]
                version = "1.0.0"
                optional = true
                """);
        Path manifest = write(original);
        ZoltManifestDocument document = parser.parseDocument(manifest);
        ProjectConfig updated = writer.removeDependency(
                document.config(),
                DependencySection.MAIN,
                "com.example:lib");

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> writer.writePreserving(manifest, document, updated));

        assertTrue(failure.getMessage().contains("long-form dependency table"));
        assertEquals(original, Files.readString(manifest));
    }

    @Test
    void addingBesideLongFormDependencyIsRejectedWithoutChangingTheManifest() throws IOException {
        String original = richManifest("""
                [dependencies.\"com.example:existing\"]
                version = "1.0.0"
                """);
        Path manifest = write(original);
        ZoltManifestDocument document = parser.parseDocument(manifest);
        ProjectConfig updated = writer.addDependency(
                document.config(),
                DependencySection.MAIN,
                "com.example:added",
                "2.0.0");

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> writer.writePreserving(manifest, document, updated));

        assertTrue(failure.getMessage().contains("long-form dependency table"));
        assertEquals(original, Files.readString(manifest));
    }

    @Test
    void multilineDependencyValueIsRejectedWithCanonicalRewriteGuidance() throws IOException {
        String original = richManifest("""
                [dependencies]
                "com.example:lib" = { version = "1.0.0", exclusions = [
                    { group = "com.example", artifact = "legacy" },
                ] }
                """);
        Path manifest = write(original);
        ZoltManifestDocument document = parser.parseDocument(manifest);
        ProjectConfig updated = writer.addDependency(
                document.config(),
                DependencySection.MAIN,
                "com.example:lib",
                "1.1.0");

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> writer.writePreserving(manifest, document, updated));

        assertTrue(failure.getMessage().contains("multiline dependency value"), failure.getMessage());
        assertTrue(failure.getMessage().contains("exclusions = ["), failure.getMessage());
        assertEquals(original, Files.readString(manifest));
    }

    @Test
    void longFormPlatformSyntaxIsRejectedWithoutChangingTheManifest() throws IOException {
        String original = richManifest("""
                [versions]
                bom = "1.0.0"

                [platforms.\"com.example:bom\"]
                versionRef = "bom"
                """);
        Path manifest = write(original);
        ZoltManifestDocument document = parser.parseDocument(manifest);
        ProjectConfig updated = writer.addPlatform(
                document.config(),
                "com.example:bom",
                "1.1.0");

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> writer.writePreserving(manifest, document, updated));

        assertTrue(failure.getMessage().contains("long-form dependency table"));
        assertEquals(original, Files.readString(manifest));
    }

    @Test
    void movingDependencyScopeRemovesOnlyTheOldAssignment() throws IOException {
        String original = richManifest("""
                [dependencies]
                # this comment describes the dependency below
                "com.example:lib" = "1.0.0"

                [runtime.dependencies]
                "com.example:runtime" = "3.0.0" # unrelated
                """);
        Path manifest = write(original);
        ZoltManifestDocument document = parser.parseDocument(manifest);
        ProjectConfig updated = writer.addManagedDependency(
                document.config(),
                DependencySection.RUNTIME,
                "com.example:lib");

        writer.writePreserving(manifest, document, updated);

        String actual = Files.readString(manifest);
        assertTrue(actual.contains("# this comment describes the dependency below"));
        assertFalse(actual.contains("\"com.example:lib\" = \"1.0.0\""));
        assertTrue(actual.contains("\"com.example:runtime\" = \"3.0.0\" # unrelated\n"
                + "\"com.example:lib\" = {}\n"));
        assertSentinelDomainsUnchanged(original, actual);
    }

    @Test
    void versionAndPlatformEditsPreserveQuotedDottedAndUnusualKeys() throws IOException {
        String original = richManifest("""
                [versions]
                "spring.boot" = "3.4.0" # alias punctuation

                [platforms]
                "com.example:platform" = { versionRef = "spring.boot" } # inline note

                [commands.tasks."verify.all"]
                command = ["zolt", "check"]
                environment = { "KEY.WITH.DOTS" = "a=b#c" }
                """);
        Path manifest = write(original);
        ZoltManifestDocument document = parser.parseDocument(manifest);
        Map<String, String> aliases = new LinkedHashMap<>(document.config().versionAliases());
        aliases.put("spring.boot", "3.5.0");
        ProjectConfig updated = document.config().withVersionAliases(aliases);

        writer.writePreserving(manifest, document, updated);

        String actual = Files.readString(manifest);
        assertTrue(actual.contains("\"spring.boot\" = \"3.5.0\" # alias punctuation"));
        assertTrue(actual.contains("\"com.example:platform\" = { versionRef = \"spring.boot\" } # inline note"));
        assertTrue(actual.contains("[commands.tasks.\"verify.all\"]\n"
                + "command = [\"zolt\", \"check\"]\n"
                + "environment = { \"KEY.WITH.DOTS\" = \"a=b#c\" }"));
        assertSentinelDomainsUnchanged(original, actual);
    }

    @Test
    void insertedAssignmentsKeepCrLfLineEndings() throws IOException {
        String original = richManifest("""
                [dependencies]
                """).replace("\n", "\r\n");
        Path manifest = write(original);
        ZoltManifestDocument document = parser.parseDocument(manifest);
        ProjectConfig updated = writer.addDependency(
                document.config(),
                DependencySection.MAIN,
                "com.example:windows",
                "1.0.0");

        writer.writePreserving(manifest, document, updated);

        String actual = Files.readString(manifest);
        assertTrue(actual.contains("\"com.example:windows\" = \"1.0.0\"\r\n"));
        assertFalse(actual.replace("\r\n", "").contains("\n"));
    }

    @Test
    void headerLikeLineInsideMultilineStringCannotHijackTableInsertion() throws IOException {
        String original = richManifest("""
                [commands]
                script = ""\"
                [dependencies]
                this is command input, not a TOML table
                ""\"
                """);
        Path manifest = write(original);
        ZoltManifestDocument document = parser.parseDocument(manifest);
        ProjectConfig updated = writer.addDependency(
                document.config(),
                DependencySection.MAIN,
                "com.example:added",
                "2.0.0");

        writer.writePreserving(manifest, document, updated);

        String actual = Files.readString(manifest);
        assertTrue(actual.contains("script = \"\"\"\n[dependencies]\nthis is command input, not a TOML table\n\"\"\""));
        assertTrue(actual.contains("\n[dependencies]\n\"com.example:added\" = \"2.0.0\"\n"));
        assertEquals(2, occurrences(actual, "[dependencies]"));
    }

    @Test
    void headerLikeLineInsideMultilineStringCannotHijackDottedInsertion() throws IOException {
        String original = """
                project.name = "dotted"
                project.version = "0.1.0"
                project.group = "com.example"
                project.java = "21"
                commands.script = ""\"
                [coverage]
                this is command input, not a TOML table
                ""\"
                dependencies."com.example:existing" = "1.0.0"
                """;
        Path manifest = write(original);
        ZoltManifestDocument document = parser.parseDocument(manifest);
        ProjectConfig updated = writer.addDependency(
                document.config(),
                DependencySection.MAIN,
                "com.example:added",
                "2.0.0");

        writer.writePreserving(manifest, document, updated);

        String actual = Files.readString(manifest);
        assertTrue(actual.contains("commands.script = \"\"\"\n[coverage]\nthis is command input, not a TOML table\n\"\"\""));
        assertTrue(actual.contains("dependencies.\"com.example:added\" = \"2.0.0\"\n"));
        assertEquals(1, occurrences(actual, "[coverage]"));
    }

    @Test
    void noOpEditLeavesDocumentByteForByteUnchanged() throws IOException {
        String original = richManifest("""
                [dependencies]
                "com.example:lib" = "1.0.0"
                """);
        Path manifest = write(original);
        ZoltManifestDocument document = parser.parseDocument(manifest);

        writer.writePreserving(manifest, document, document.config());

        assertEquals(original, Files.readString(manifest));
    }

    @Test
    void concurrentManualEditIsRejectedWithoutOverwritingIt() throws IOException {
        String original = richManifest("""
                [dependencies]
                "com.example:lib" = "1.0.0"
                """);
        Path manifest = write(original);
        ZoltManifestDocument document = parser.parseDocument(manifest);
        ProjectConfig updated = writer.addDependency(
                document.config(),
                DependencySection.TEST,
                "com.example:test-lib",
                "1.0.0");
        String manuallyEdited = original + "\n# concurrent edit\n";
        Files.writeString(manifest, manuallyEdited);

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> writer.writePreserving(manifest, document, updated));

        assertTrue(failure.getMessage().contains("changed while the edit was in progress"));
        assertEquals(manuallyEdited, Files.readString(manifest));
    }

    private Path write(String source) throws IOException {
        Path manifest = tempDir.resolve("zolt.toml");
        Files.writeString(manifest, source);
        return manifest;
    }

    private static String richManifest(String editableSections) {
        return """
                # project identity stays first
                [project]
                name = "preservation"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                %s
                [coverage]
                minLine = 88.0 # release floor
                minBranch = 74.0

                [toolchain.java]
                version = "21"
                distribution = "graalvm-community"
                features = ["native-image"]
                policy = "require-managed"

                [publish.central]
                automatic = false

                [commands.aliases]
                verify = ["check", "--all"]

                [workspace]
                name = "preservation"
                members = []
                """.formatted(editableSections);
    }

    private static void assertSentinelDomainsUnchanged(String before, String after) {
        for (String header : new String[] {
                "[coverage]",
                "[toolchain.java]",
                "[publish.central]",
                "[commands.aliases]",
                "[workspace]"
        }) {
            assertEquals(section(before, header), section(after, header), header);
        }
        assertTrue(after.startsWith("# project identity stays first\n[project]"));
    }

    private static String section(String source, String header) {
        int start = source.indexOf(header);
        int next = source.indexOf("\n[", start + header.length());
        return source.substring(start, next < 0 ? source.length() : next);
    }

    private static int occurrences(String source, String needle) {
        return source.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
