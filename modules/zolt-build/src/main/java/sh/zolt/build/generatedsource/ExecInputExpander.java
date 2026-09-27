package sh.zolt.build.generatedsource;

import sh.zolt.build.BuildException;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Expands an exec step's declared input (a literal path or a glob) into the concrete project-relative
 * files Zolt fingerprints. Zolt owns glob expansion; the tool never sees an unexpanded pattern.
 */
public final class ExecInputExpander {
    private ExecInputExpander() {
    }

    static boolean isGlob(String input) {
        return input.indexOf('*') >= 0 || input.indexOf('?') >= 0 || input.indexOf('[') >= 0;
    }

    static List<Path> expand(Path projectRoot, String input) {
        if (!isGlob(input)) {
            return List.of(projectRoot.resolve(input).normalize());
        }
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + input);
        List<Path> matches = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(projectRoot)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                Path normalized = path.normalize();
                if (matcher.matches(projectRoot.relativize(normalized))) {
                    // Every concrete match is routed through the hardened real-path helper: a matched file
                    // whose real path escapes the project (e.g. a project-local symlink to an external file)
                    // is rejected rather than silently hashed into the fingerprint or fed to the tool.
                    requireInsideProject(projectRoot, input, normalized);
                    matches.add(normalized);
                }
            });
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not expand exec input glob `" + input + "`. Check that the project directory is readable.",
                    exception);
        }
        matches.sort(null);
        return List.copyOf(matches);
    }

    private static void requireInsideProject(Path projectRoot, String input, Path match) {
        try {
            ProjectPaths.requireExistingInsideProject(projectRoot, "inputs", input, match);
        } catch (ProjectPathException exception) {
            throw BuildException.actionable(
                    "Exec input glob `" + input + "` matched " + match
                            + ", which resolves through a symlink outside the project directory.",
                    "Remove the escaping symlink or narrow the glob so every match stays under the project root.");
        }
    }

    /**
     * The non-glob leading directory of an input, used only for ordering-edge and target/classes
     * detection (so a glob input can still be located relative to another step's output).
     */
    public static String literalBase(String input) {
        int glob = -1;
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if (character == '*' || character == '?' || character == '[') {
                glob = index;
                break;
            }
        }
        if (glob < 0) {
            return input;
        }
        String prefix = input.substring(0, glob);
        int slash = prefix.lastIndexOf('/');
        return slash < 0 ? "" : prefix.substring(0, slash);
    }
}
