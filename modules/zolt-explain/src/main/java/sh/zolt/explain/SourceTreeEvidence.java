package sh.zolt.explain;

import sh.zolt.project.ProjectPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/** Ownership and language facts discovered without following source-tree symbolic links. */
public record SourceTreeEvidence(
        boolean mainJavaSourcesPresent,
        boolean testJavaSourcesPresent,
        boolean modularSources,
        boolean sourceLinksPresent) {
    public static SourceTreeEvidence inspect(
            String sourceKind,
            Path projectDirectory,
            List<String> mainRoots,
            List<String> testRoots) {
        Scan main = scan(sourceKind, projectDirectory, mainRoots);
        Scan test = scan(sourceKind, projectDirectory, testRoots);
        return new SourceTreeEvidence(
                main.javaSourcesPresent(),
                test.javaSourcesPresent(),
                main.modularSources() || test.modularSources(),
                main.sourceLinksPresent() || test.sourceLinksPresent());
    }

    public static SourceTreeEvidence none() {
        return new SourceTreeEvidence(false, false, false, false);
    }

    private static Scan scan(
            String sourceKind,
            Path projectDirectory,
            List<String> roots) {
        Path projectRoot = projectDirectory.toAbsolutePath().normalize();
        boolean javaSources = false;
        boolean modularSources = false;
        boolean sourceLinks = false;
        for (String root : roots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            Path sourceRoot = projectDirectory.resolve(root).toAbsolutePath().normalize();
            if (!sourceRoot.startsWith(projectRoot)
                    || symbolicComponent(projectRoot, sourceRoot)) {
                sourceLinks = true;
                continue;
            }
            if (!Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                Iterator<Path> paths = files.iterator();
                while (paths.hasNext()) {
                    Path path = paths.next();
                    if (Files.isSymbolicLink(path)) {
                        sourceLinks = true;
                        continue;
                    }
                    if (!javaSource(projectRoot, sourceKind, root, path)) {
                        continue;
                    }
                    javaSources = true;
                    if (path.getFileName().toString().equals("module-info.java")) {
                        modularSources = true;
                    }
                }
            } catch (IOException exception) {
                throw new MigrationExplainException(
                        "Could not inspect " + sourceKind + " source root `" + root + "`: "
                                + exception.getMessage(),
                        exception);
            }
        }
        return new Scan(javaSources, modularSources, sourceLinks);
    }

    private static boolean javaSource(
            Path projectRoot,
            String sourceKind,
            String sourceRoot,
            Path path) {
        return path.getFileName() != null
                && path.getFileName().toString().endsWith(".java")
                && ProjectPaths.isRegularFileInsideProject(
                        projectRoot, sourceKind + " source root `" + sourceRoot + "`", path);
    }

    private static boolean symbolicComponent(Path projectRoot, Path sourceRoot) {
        Path current = projectRoot;
        for (Path component : projectRoot.relativize(sourceRoot)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private record Scan(
            boolean javaSourcesPresent,
            boolean modularSources,
            boolean sourceLinksPresent) {
    }
}
