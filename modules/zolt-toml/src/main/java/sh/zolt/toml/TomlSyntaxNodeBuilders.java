package sh.zolt.toml;

import java.util.List;
import java.util.Optional;

/** Internal mutable assembly state converted to immutable public syntax nodes after scanning. */
final class TomlSyntaxNodeBuilders {
    private TomlSyntaxNodeBuilders() {
    }

    record Header(
            List<String> path,
            SourceSpan headerSpan,
            boolean arrayTable,
            int lineEnd) {
        Header {
            path = List.copyOf(path);
        }
    }

    record Assignment(
            List<String> keyPath,
            SourceSpan keySpan,
            SourceSpan valueSpan,
            SourceSpan assignmentSpan,
            SourceSpan lineSpan,
            Optional<SourceSpan> trailingCommentSpan,
            int lineEnd) {
        AssignmentSyntax syntax(int sourceOrder, List<String> tablePath) {
            return new AssignmentSyntax(
                    tablePath,
                    keyPath,
                    keySpan,
                    valueSpan,
                    assignmentSpan,
                    lineSpan,
                    trailingCommentSpan,
                    sourceOrder);
        }
    }

    static final class TableBuilder {
        private final List<String> path;
        private final SourceSpan headerSpan;
        private final int bodyStart;
        private final boolean explicit;
        private final boolean arrayTable;
        private final int sourceOrder;
        private int bodyEnd;

        private TableBuilder(
                List<String> path,
                SourceSpan headerSpan,
                int bodyStart,
                boolean explicit,
                boolean arrayTable,
                int sourceOrder) {
            this.path = path;
            this.headerSpan = headerSpan;
            this.bodyStart = bodyStart;
            this.bodyEnd = bodyStart;
            this.explicit = explicit;
            this.arrayTable = arrayTable;
            this.sourceOrder = sourceOrder;
        }

        static TableBuilder implicit(List<String> path, int anchor, int sourceOrder) {
            return new TableBuilder(
                    path,
                    SourceSpan.emptyAt(anchor),
                    anchor,
                    false,
                    false,
                    sourceOrder);
        }

        static TableBuilder explicit(
                List<String> path,
                SourceSpan headerSpan,
                int bodyStart,
                boolean arrayTable,
                int sourceOrder) {
            return new TableBuilder(path, headerSpan, bodyStart, true, arrayTable, sourceOrder);
        }

        void closeBodyAt(int offset) {
            if (offset < bodyStart) {
                throw TomlSourceScanner.fail(offset, "table body ended before it began");
            }
            bodyEnd = offset;
        }

        TableSyntax syntax() {
            return new TableSyntax(
                    path,
                    headerSpan,
                    new SourceSpan(bodyStart, bodyEnd),
                    explicit,
                    arrayTable,
                    sourceOrder);
        }
    }
}
