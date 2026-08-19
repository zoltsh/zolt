package sh.zolt.toml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class TomlSyntaxParserTest {
    private final TomlSyntaxParser parser = new TomlSyntaxParser();

    @Test
    void indexesUtf16CrLfCommentsAndExactTableBodies() {
        String source = ("""
                title = "🚀"
                # root comment
                [project] # identity
                name = "demo" # retained
                [dependencies]
                "g:a" = { version = "1", reason = "contains # and =" }
                """).replace("\n", "\r\n");

        ManifestSyntax syntax = parser.parse(source);
        assertEquals(3, syntax.tables().size());
        TableSyntax root = syntax.tables().get(0);
        TableSyntax project = syntax.tables().get(1);
        TableSyntax dependencies = syntax.tables().get(2);

        assertEquals(List.of(), root.path());
        assertFalse(root.explicit());
        assertTrue(root.headerSpan().isEmpty());
        assertEquals(source.substring(0, source.indexOf("[project]")), root.bodySpan().text(source));

        assertEquals(List.of("project"), project.path());
        assertEquals("[project]", project.headerSpan().text(source));
        assertEquals(
                "name = \"demo\" # retained\r\n",
                project.bodySpan().text(source));
        assertEquals(List.of("dependencies"), dependencies.path());
        assertEquals("[dependencies]", dependencies.headerSpan().text(source));
        assertEquals(
                "\"g:a\" = { version = \"1\", reason = \"contains # and =\" }\r\n",
                dependencies.bodySpan().text(source));

        AssignmentSyntax name = syntax.sourceIndex()
                .assignmentsAt(List.of("project", "name"))
                .getFirst();
        assertEquals("name", name.keySpan().text(source));
        assertEquals("\"demo\"", name.valueSpan().text(source));
        assertEquals("name = \"demo\"", name.assignmentSpan().text(source));
        assertEquals("name = \"demo\" # retained\r\n", name.lineSpan().text(source));
        assertEquals("# retained", name.trailingCommentSpan().orElseThrow().text(source));

        int utf16Offset = source.indexOf("[project]");
        int codePointOffset = source.codePointCount(0, utf16Offset);
        assertEquals(codePointOffset + 1, utf16Offset);
        assertEquals(utf16Offset, project.headerSpan().start());
    }

    @Test
    void decodesQuotedDottedAndEmptyKeysWithoutLosingAuthoredSpelling() {
        String source = """
                dependencies . "org.slf4j:slf4j-api" = { version = "2.0.17", because = "# =" }
                "" = "blank"

                [ "publish" . repositories . 'in.ternal' ]
                "a.b" . child = "value"

                ["a#=b"]
                quoted = "#="
                """;

        ManifestSyntax syntax = parser.parse(source);
        AssignmentSyntax dependency = syntax.assignments().get(0);
        assertEquals(List.of(), dependency.tablePath());
        assertEquals(List.of("dependencies", "org.slf4j:slf4j-api"), dependency.keyPath());
        assertEquals(
                "dependencies . \"org.slf4j:slf4j-api\"",
                dependency.keySpan().text(source));
        assertEquals(
                "{ version = \"2.0.17\", because = \"# =\" }",
                dependency.valueSpan().text(source));

        assertEquals(List.of(""), syntax.assignments().get(1).keyPath());
        TableSyntax table = syntax.sourceIndex()
                .explicitTablesAt(List.of("publish", "repositories", "in.ternal"))
                .getFirst();
        assertEquals("[ \"publish\" . repositories . 'in.ternal' ]", table.headerSpan().text(source));
        AssignmentSyntax child = syntax.assignments().get(2);
        assertEquals(List.of("publish", "repositories", "in.ternal"), child.tablePath());
        assertEquals(List.of("a.b", "child"), child.keyPath());
        assertEquals(
                List.of("publish", "repositories", "in.ternal", "a.b", "child"),
                child.fullPath());
        TableSyntax punctuationTable = syntax.sourceIndex()
                .explicitTablesAt(List.of("a#=b"))
                .getFirst();
        assertEquals("[\"a#=b\"]", punctuationTable.headerSpan().text(source));
        assertEquals("\"#=\"", syntax.assignments().get(3).valueSpan().text(source));

        String emptyTableSource = "[\"\"]\nvalue = 1\n";
        ManifestSyntax emptyTableSyntax = parser.parse(emptyTableSource);
        TableSyntax emptyTable = emptyTableSyntax.sourceIndex()
                .explicitTablesAt(List.of(""))
                .getFirst();
        assertEquals("[\"\"]", emptyTable.headerSpan().text(emptyTableSource));
        assertEquals(List.of(""), emptyTableSyntax.assignments().getFirst().tablePath());
    }

    @Test
    void capturesNestedArraysInlineTablesAndInternalCommentsAsOneValue() {
        String source = """
                [generated]
                plan = [
                  "a#=b",
                  # an internal array comment
                  { key = "v#=x", nested = [1, 2] },
                ] # keep plan
                next = true
                """;

        ManifestSyntax syntax = parser.parse(source);
        AssignmentSyntax plan = syntax.sourceIndex()
                .assignmentsAt(List.of("generated", "plan"))
                .getFirst();
        assertEquals("""
                [
                  "a#=b",
                  # an internal array comment
                  { key = "v#=x", nested = [1, 2] },
                ]""", plan.valueSpan().text(source));
        assertEquals("# keep plan", plan.trailingCommentSpan().orElseThrow().text(source));
        assertEquals("""
                plan = [
                  "a#=b",
                  # an internal array comment
                  { key = "v#=x", nested = [1, 2] },
                ] # keep plan
                """, plan.lineSpan().text(source));
        assertEquals(
                "true",
                syntax.sourceIndex()
                        .assignmentsAt(List.of("generated", "next"))
                        .getFirst()
                        .valueSpan()
                        .text(source));
    }

    @Test
    void capturesMultilineStringsIncludingHashesEqualsAndClosingQuoteRuns() {
        String source = "basic = \"\"\"\n"
                + "first # =\n"
                + "second\"\"\"\" # four-quote close\n"
                + "literal = '''\n"
                + "first # =\n"
                + "second''''' # five-quote close\n";

        ManifestSyntax syntax = parser.parse(source);
        AssignmentSyntax basic = syntax.assignments().get(0);
        AssignmentSyntax literal = syntax.assignments().get(1);
        assertEquals("\"\"\"\nfirst # =\nsecond\"\"\"\"", basic.valueSpan().text(source));
        assertEquals("# four-quote close", basic.trailingCommentSpan().orElseThrow().text(source));
        assertEquals("'''\nfirst # =\nsecond'''''", literal.valueSpan().text(source));
        assertEquals("# five-quote close", literal.trailingCommentSpan().orElseThrow().text(source));
    }

    @Test
    void distinguishesImplicitExplicitAndRepeatedArrayTables() {
        String source = """
                alpha.beta = 1

                [one.two]
                x = 2

                [[items]]
                name = "first"

                [[items]]
                name = "second"
                """;

        ManifestSyntax syntax = parser.parse(source);
        assertEquals(List.of(), syntax.tables().get(0).path());
        assertEquals(List.of("alpha"), syntax.tables().get(1).path());
        assertFalse(syntax.tables().get(1).explicit());
        assertTrue(syntax.tables().get(1).bodySpan().isEmpty());
        assertEquals(List.of("one"), syntax.tables().get(2).path());
        assertFalse(syntax.tables().get(2).explicit());
        assertEquals(List.of("one", "two"), syntax.tables().get(3).path());
        assertTrue(syntax.tables().get(3).explicit());

        List<TableSyntax> items = syntax.sourceIndex().explicitTablesAt(List.of("items"));
        assertEquals(2, items.size());
        assertTrue(items.stream().allMatch(TableSyntax::arrayTable));
        assertEquals("[[items]]", items.get(0).headerSpan().text(source));
        assertEquals("[[items]]", items.get(1).headerSpan().text(source));
        assertEquals(2, syntax.sourceIndex().assignmentsAt(List.of("items", "name")).size());
    }

    @Test
    void lineSpansHandleEofWithAndWithoutANewline() {
        String withoutNewline = "key = \"value\" # eof";
        AssignmentSyntax eof = parser.parse(withoutNewline).assignments().getFirst();
        assertEquals(withoutNewline, eof.lineSpan().text(withoutNewline));
        assertEquals("# eof", eof.trailingCommentSpan().orElseThrow().text(withoutNewline));

        String withNewline = withoutNewline + "\n";
        AssignmentSyntax terminated = parser.parse(withNewline).assignments().getFirst();
        assertEquals(withNewline, terminated.lineSpan().text(withNewline));
        assertEquals(withNewline.length(), terminated.lineSpan().end());
    }

    @Test
    void syntaxNodesAndIndexesExposeImmutableCollections() {
        ManifestSyntax syntax = parser.parse("a.b = 1\n");
        assertThrows(UnsupportedOperationException.class, () -> syntax.tables().clear());
        assertThrows(UnsupportedOperationException.class, () -> syntax.assignments().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> syntax.tables().get(1).path().add("changed"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> syntax.assignments().getFirst().keyPath().add("changed"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> syntax.sourceIndex().assignmentsAt(List.of("a", "b")).clear());
    }

    @Test
    void rejectsInvalidTomlBeforeCreatingSourceSpans() {
        ZoltConfigException exception = assertThrows(
                ZoltConfigException.class,
                () -> parser.parse("[project\nname = \"broken\"\n"));
        assertTrue(exception.getMessage().startsWith("Could not parse zolt.toml."));
    }
}
