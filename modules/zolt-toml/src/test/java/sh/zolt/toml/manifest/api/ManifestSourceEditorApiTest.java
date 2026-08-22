package sh.zolt.toml.manifest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.toml.manifest.ZoltManifestDocument;
import sh.zolt.toml.manifest.ZoltManifestParser;
import sh.zolt.toml.manifest.edit.ManifestSourceEditor;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.MutationPolicy;

final class ManifestSourceEditorApiTest {
    private static final String PROJECT = "[project]\nname = \"demo\"\n";
    private final ZoltManifestParser parser = new ZoltManifestParser();
    private final ManifestSourceEditor editor = new ManifestSourceEditor();

    @Test
    void replacesOnlyTheCompleteValueSpan() {
        String source = PROJECT + """

                [versions]
                # retained standalone comment
                library  = '1.0'   # retained entry comment
                """;
        String expected = source.replace("'1.0'", "\"2.0\"");

        ZoltManifestDocument edited = edit(source, PROJECT + """

                [versions]
                library = "2.0"
                """);

        assertEquals(expected, edited.source());
    }

    @Test
    void removesOnlyTheAssignmentLineAndRetainsTheEmptyTable() {
        String source = PROJECT + """

                [versions]
                # not owned by the entry
                library = "1.0" # removed with the entry
                """;

        ZoltManifestDocument edited = edit(source, PROJECT + "\n[versions]\n");

        assertEquals(PROJECT + """

                [versions]
                # not owned by the entry
                """, edited.source());
    }

    @Test
    void appendsToAnExistingTableWithoutNormalizingCrlf() {
        String source = PROJECT.replace("\n", "\r\n")
                + "\r\n[versions]\r\n# retained\r\n";

        ZoltManifestDocument edited = edit(source, PROJECT + """

                [versions]
                library = "1.0"
                """);

        assertEquals(PROJECT.replace("\n", "\r\n")
                + "\r\n[versions]\r\nlibrary = \"1.0\"\r\n# retained\r\n", edited.source());
    }

    @Test
    void rejectsMixedLineEndingsBeforeAddingSource() {
        String source = PROJECT + "\r\n[versions]\r\n";

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> edit(source, PROJECT + """

                        [versions]
                        library = "1.0"
                        """));

        assertEquals(
                "Could not safely edit zolt.toml because the captured source mixes LF and CRLF "
                        + "line endings. No changes were written.",
                failure.getMessage());
    }

    @Test
    void createsAMissingTableAtTheUnambiguousSchemaBoundary() {
        String source = PROJECT + """

                [dependencies.test]
                "org.junit.jupiter:junit-jupiter" = "5.13.4"
                """;
        String requested = PROJECT + """

                [versions]
                library = "2.0"

                [dependencies.test]
                "org.junit.jupiter:junit-jupiter" = "5.13.4"
                """;

        ZoltManifestDocument edited = edit(source, requested);

        assertEquals(requested, edited.source());
    }

    @Test
    void carriesAnEntryCommentAcrossDependencyLanes() {
        String source = PROJECT + """

                [dependencies]
                # remains in the original lane
                "org.example:demo" = "1.0"  # moves with the entry

                [dependencies.test]
                "org.junit.jupiter:junit-jupiter" = "5.13.4"
                """;
        String requested = PROJECT + """

                [dependencies.runtime]
                "org.example:demo" = "1.0"

                [dependencies.test]
                "org.junit.jupiter:junit-jupiter" = "5.13.4"
                """;

        ZoltManifestDocument edited = edit(source, requested);

        assertEquals(PROJECT + """

                [dependencies]
                # remains in the original lane

                [dependencies.runtime]
                "org.example:demo" = "1.0"  # moves with the entry

                [dependencies.test]
                "org.junit.jupiter:junit-jupiter" = "5.13.4"
                """, edited.source());
        assertEquals(parser.parse(requested).authored(), edited.authored());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutableTables")
    void routesEveryFrozenMutableTableThroughSchemaMetadata(
            String table,
            String key) {
        String source = PROJECT + "\n[" + table + "]\n" + key + " = \"1.0\"\n";
        String requested = source.replace("\"1.0\"", "\"2.0\"");

        assertEquals(requested, edit(source, requested).source());
    }

    @Test
    void schemaDeclaresExactlyTheFrozenMutableTables() {
        Set<String> paths = FinalManifestSchema.registry().fields().stream()
                .filter(field -> field.mutation() == MutationPolicy.REPLACE_ENTRY)
                .map(field -> String.join(".", field.path().segments()
                        .subList(0, field.path().segments().size() - 1)))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        assertEquals(Set.of(
                "versions",
                "platforms",
                "dependencies",
                "dependencies.api",
                "dependencies.runtime",
                "dependencies.provided",
                "dependencies.dev",
                "dependencies.test",
                "dependencies.processor",
                "dependencies.test-processor",
                "dependencies.constraints",
                "bom.versions",
                "bom.imports"), paths);
    }

    @Test
    void failsClosedWhenTheRequestedManifestChangesAnImmutableField() {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> edit(PROJECT, "[project]\nname = \"changed\"\n"));

        assertEquals(
                "Could not safely edit zolt.toml because the source patch does not equal the "
                        + "requested manifest. No changes were written.",
                failure.getMessage());
    }

    @Test
    void rejectsAuthoredEvidenceThatDoesNotMatchTheCapturedSource() {
        ZoltManifestDocument captured = parser.parse(PROJECT);
        ZoltManifestDocument different = parser.parse("[project]\nname = \"changed\"\n");
        ZoltManifestDocument forged = new ZoltManifestDocument(
                captured.source(), captured.syntax(), different.authored());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> editor.edit(forged, different.authored()));

        assertEquals(
                "Could not safely edit zolt.toml because the retained authored model does not "
                        + "match the captured source. No changes were written.",
                failure.getMessage());
    }

    @Test
    void returnsTheOriginalDocumentForANoop() {
        ZoltManifestDocument original = parser.parse(PROJECT);

        assertSame(original, editor.edit(original, original.authored()));
    }

    @Test
    void rejectsNullInputs() {
        ZoltManifestDocument original = parser.parse(PROJECT);

        assertEquals(
                "Original manifest document is required.",
                assertThrows(NullPointerException.class, () ->
                        editor.edit(null, original.authored())).getMessage());
        assertEquals(
                "Requested authored manifest is required.",
                assertThrows(NullPointerException.class, () ->
                        editor.edit(original, null)).getMessage());
    }

    private ZoltManifestDocument edit(String source, String requested) {
        return editor.edit(parser.parse(source), parser.parse(requested).authored());
    }

    private static Stream<Arguments> mutableTables() {
        return Stream.of(
                Arguments.of("versions", "library"),
                Arguments.of("platforms", "\"org.example:platform\""),
                Arguments.of("dependencies", "\"org.example:demo\""),
                Arguments.of("dependencies.api", "\"org.example:demo\""),
                Arguments.of("dependencies.runtime", "\"org.example:demo\""),
                Arguments.of("dependencies.provided", "\"org.example:demo\""),
                Arguments.of("dependencies.dev", "\"org.example:demo\""),
                Arguments.of("dependencies.test", "\"org.example:demo\""),
                Arguments.of("dependencies.processor", "\"org.example:demo\""),
                Arguments.of("dependencies.test-processor", "\"org.example:demo\""),
                Arguments.of("dependencies.constraints", "\"org.example:demo\""),
                Arguments.of("bom.versions", "\"org.example:demo\""),
                Arguments.of("bom.imports", "\"org.example:demo-bom\""));
    }
}
