package sh.zolt.build.incremental;

import sh.zolt.build.BuildException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

record IncrementalCompileOutputDigests(
        String publicAbiDigest,
        String packagePrivateAbiDigest,
        String outputManifestDigest) {
    static IncrementalCompileOutputDigests fromClasses(
            List<IncrementalCompileState.ClassRecord> classes) {
        return calculate(classes, List.of());
    }

    static IncrementalCompileOutputDigests capture(
            Path outputDirectory,
            List<IncrementalCompileState.ClassRecord> classes) {
        return calculate(classes, kotlinModuleEntries(outputDirectory));
    }

    private static IncrementalCompileOutputDigests calculate(
            List<IncrementalCompileState.ClassRecord> classes,
            List<String> kotlinModules) {
        List<String> publicAbi = new ArrayList<>(kotlinModules);
        List<String> packagePrivateAbi = new ArrayList<>(kotlinModules);
        List<String> outputManifest = new ArrayList<>(kotlinModules);
        for (IncrementalCompileState.ClassRecord value : classes) {
            if (value.externallyVisible()) {
                publicAbi.add(value.binaryName() + "|" + value.abiHash());
            }
            packagePrivateAbi.add(value.binaryName() + "|" + value.packagePrivateAbiHash());
            outputManifest.add(value.binaryName() + "|" + value.classFileHash());
        }
        return new IncrementalCompileOutputDigests(
                digest(publicAbi),
                digest(packagePrivateAbi),
                digest(outputManifest));
    }

    private static List<String> kotlinModuleEntries(Path outputDirectory) {
        Path outputRoot = outputDirectory.toAbsolutePath().normalize();
        Path metadata = outputRoot.resolve("META-INF");
        if (!Files.isDirectory(metadata, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(metadata)) {
            return paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().endsWith(".kotlin_module"))
                    .map(Path::normalize)
                    .sorted()
                    .map(path -> kotlinModuleEntry(outputRoot, path))
                    .toList();
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not inspect Kotlin module metadata under "
                            + metadata
                            + ". Check that the directory is readable.",
                    exception);
        }
    }

    private static String kotlinModuleEntry(Path outputRoot, Path path) {
        String contentHash = IncrementalCompileInputHasher.hash(path);
        if ("missing".equals(contentHash)) {
            throw new BuildException(
                    "Kotlin module metadata disappeared while recording incremental compile state at "
                            + path
                            + ". Retry the build after ensuring the output directory is not being modified concurrently.");
        }
        return "kotlin-module|" + normalize(outputRoot.relativize(path)) + "|" + contentHash;
    }

    private static String digest(List<String> entries) {
        return IncrementalCompileInputHasher.hashText(
                String.join("\n", entries.stream().sorted().toList()));
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }
}
