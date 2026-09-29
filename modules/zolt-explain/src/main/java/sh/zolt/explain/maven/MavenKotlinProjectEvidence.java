package sh.zolt.explain.maven;

import static sh.zolt.explain.maven.MavenXml.child;

import sh.zolt.explain.MigrationExplainException;
import sh.zolt.project.ProjectPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.w3c.dom.Element;

/** Project-level evidence used to decide whether a Maven Kotlin draft is lossless. */
record MavenKotlinProjectEvidence(
        String compilerRelease,
        String compilerProc,
        String sourceEncoding,
        List<String> compilerProperties,
        boolean explicitSourceDirectory,
        boolean explicitTestSourceDirectory,
        boolean groovyTestSourcesPresent,
        boolean modularSources,
        boolean sourceLinksPresent) {
    MavenKotlinProjectEvidence {
        compilerProperties = List.copyOf(compilerProperties);
    }

    static MavenKotlinProjectEvidence inspect(
            Element project,
            Path projectDirectory,
            MavenPomProperties properties,
            List<String> sourceRoots,
            List<String> testSourceRoots) {
        return new MavenKotlinProjectEvidence(
                properties.interpolate(properties.values().get("maven.compiler.release")).strip(),
                properties.interpolate(properties.values().get("maven.compiler.proc")).strip(),
                properties.interpolate(properties.values().get("project.build.sourceEncoding")).strip(),
                compilerProperties(properties),
                buildElement(project, "sourceDirectory"),
                buildElement(project, "testSourceDirectory"),
                Files.isDirectory(projectDirectory.resolve("src/test/groovy")),
                containsModuleInfo(projectDirectory, sourceRoots)
                        || containsModuleInfo(projectDirectory, testSourceRoots),
                containsSourceLink(projectDirectory, sourceRoots)
                        || containsSourceLink(projectDirectory, testSourceRoots));
    }

    private static List<String> compilerProperties(MavenPomProperties properties) {
        return properties.values().keySet().stream()
                .filter(name -> name.startsWith("maven.compiler.")
                        || name.equals("project.build.sourceEncoding")
                        || name.equals("encoding")
                        || name.equals("maven.main.skip")
                        || name.equals("maven.test.skip"))
                .sorted()
                .toList();
    }

    private static boolean buildElement(Element project, String name) {
        return child(project, "build").flatMap(build -> child(build, name)).isPresent();
    }

    private static boolean containsModuleInfo(Path projectDirectory, List<String> roots) {
        Path ownedRoot = projectDirectory.toAbsolutePath().normalize();
        for (String root : roots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            Path sourceRoot = projectDirectory.resolve(root).toAbsolutePath().normalize();
            if (!sourceRoot.startsWith(ownedRoot)
                    || !Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                if (files.anyMatch(path -> moduleInfo(ownedRoot, root, path))) {
                    return true;
                }
            } catch (IOException exception) {
                throw new MigrationExplainException(
                        "Could not inspect Maven Kotlin source root `" + root + "`: "
                                + exception.getMessage(),
                        exception);
            }
        }
        return false;
    }

    private static boolean containsSourceLink(Path projectDirectory, List<String> roots) {
        Path ownedRoot = projectDirectory.toAbsolutePath().normalize();
        for (String root : roots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            Path sourceRoot = projectDirectory.resolve(root).toAbsolutePath().normalize();
            if (symbolicComponent(ownedRoot, sourceRoot)) {
                return true;
            }
            if (!Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                if (files.anyMatch(Files::isSymbolicLink)) {
                    return true;
                }
            } catch (IOException exception) {
                throw new MigrationExplainException(
                        "Could not inspect Maven source root `" + root + "` for symbolic links: "
                                + exception.getMessage(),
                        exception);
            }
        }
        return false;
    }

    private static boolean symbolicComponent(Path projectRoot, Path sourceRoot) {
        if (!sourceRoot.startsWith(projectRoot)) {
            return true;
        }
        Path current = projectRoot;
        for (Path component : projectRoot.relativize(sourceRoot)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean moduleInfo(Path projectRoot, String sourceRoot, Path path) {
        return path.getFileName() != null
                && path.getFileName().toString().equals("module-info.java")
                && !Files.isSymbolicLink(path)
                && ProjectPaths.isRegularFileInsideProject(
                        projectRoot, "Maven source root `" + sourceRoot + "`", path);
    }
}
