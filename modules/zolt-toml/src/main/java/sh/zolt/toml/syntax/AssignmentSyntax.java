package sh.zolt.toml.syntax;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact source ranges for one TOML key/value assignment.
 *
 * <p>{@code lineSpan} includes indentation, a same-line comment, and the terminating newline when
 * present. For a multiline value it covers every physical line occupied by the assignment.
 */
public record AssignmentSyntax(
        List<String> tablePath,
        List<String> keyPath,
        SourceSpan keySpan,
        SourceSpan valueSpan,
        SourceSpan assignmentSpan,
        SourceSpan lineSpan,
        Optional<SourceSpan> trailingCommentSpan,
        int sourceOrder) {
    public AssignmentSyntax {
        tablePath = immutablePath(tablePath, "Assignment table path is required.");
        keyPath = immutablePath(keyPath, "Assignment key path is required.");
        if (keyPath.isEmpty()) {
            throw new IllegalArgumentException("Assignment key path must not be empty.");
        }
        Objects.requireNonNull(keySpan, "Assignment key span is required.");
        Objects.requireNonNull(valueSpan, "Assignment value span is required.");
        Objects.requireNonNull(assignmentSpan, "Assignment span is required.");
        Objects.requireNonNull(lineSpan, "Assignment line span is required.");
        trailingCommentSpan = Objects.requireNonNull(
                trailingCommentSpan, "Assignment trailing comment span must not be null.");
        if (sourceOrder < 0) {
            throw new IllegalArgumentException("Assignment source order must not be negative.");
        }
        if (assignmentSpan.start() != keySpan.start()
                || assignmentSpan.end() != valueSpan.end()
                || keySpan.end() > valueSpan.start()) {
            throw new IllegalArgumentException("Assignment component spans are inconsistent.");
        }
        if (lineSpan.start() > assignmentSpan.start() || lineSpan.end() < assignmentSpan.end()) {
            throw new IllegalArgumentException("Assignment line span must enclose the assignment.");
        }
        trailingCommentSpan.ifPresent(comment -> {
            if (comment.start() < valueSpan.end() || comment.end() > lineSpan.end()) {
                throw new IllegalArgumentException("Trailing comment span must follow the value on its line.");
            }
        });
    }

    public List<String> fullPath() {
        ArrayList<String> path = new ArrayList<>(tablePath.size() + keyPath.size());
        path.addAll(tablePath);
        path.addAll(keyPath);
        return List.copyOf(path);
    }

    private static List<String> immutablePath(List<String> path, String message) {
        return List.copyOf(Objects.requireNonNull(path, message));
    }
}
