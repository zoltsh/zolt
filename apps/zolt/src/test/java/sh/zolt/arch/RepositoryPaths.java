package sh.zolt.arch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class RepositoryPaths {
    private RepositoryPaths() {
    }

    static Path root() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (isWorkspaceRoot(current)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate repository root from current working directory.");
    }

    static Path appRoot() {
        return root().resolve("apps/zolt");
    }

    static List<Path> mainSourceRoots() {
        return sourceRoots("src/main/java");
    }

    static List<Path> testSourceRoots() {
        return sourceRoots("src/test/java");
    }

    static String displayPath(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path repositoryRoot = root().toAbsolutePath().normalize();
        if (normalized.startsWith(repositoryRoot)) {
            return repositoryRoot.relativize(normalized).toString().replace('\\', '/');
        }
        String value = normalized.toString().replace('\\', '/');
        int sourceIndex = value.indexOf("/src/");
        return sourceIndex >= 0 ? value.substring(sourceIndex + 1) : value;
    }

    private static List<Path> sourceRoots(String relativeSourceRoot) {
        Path repositoryRoot = root();
        List<Path> sourceRoots = new ArrayList<>();
        addModuleSourceRoots(sourceRoots, repositoryRoot.resolve("apps"), relativeSourceRoot);
        addModuleSourceRoots(sourceRoots, repositoryRoot.resolve("modules"), relativeSourceRoot);
        return List.copyOf(sourceRoots);
    }

    private static boolean isWorkspaceRoot(Path current) {
        Path rootConfig = current.resolve("zolt.toml");
        if (!Files.isRegularFile(rootConfig)) {
            return false;
        }
        try {
            return Files.readString(rootConfig).lines().map(String::trim).anyMatch("[workspace]"::equals);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read root config at " + rootConfig + ".", exception);
        }
    }

    private static void addModuleSourceRoots(List<Path> sourceRoots, Path modulesRoot, String relativeSourceRoot) {
        if (!Files.isDirectory(modulesRoot)) {
            return;
        }
        try (var paths = Files.list(modulesRoot)) {
            paths.map(path -> path.resolve(relativeSourceRoot))
                    .filter(Files::isDirectory)
                    .sorted()
                    .forEach(sourceRoots::add);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not list source roots under " + modulesRoot + ".", exception);
        }
    }
}
