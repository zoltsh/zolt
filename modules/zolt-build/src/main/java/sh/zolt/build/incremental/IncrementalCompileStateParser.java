package sh.zolt.build.incremental;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class IncrementalCompileStateParser {
    Optional<IncrementalCompileState> parse(String content) {
        try {
            return parseStrict(content);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private Optional<IncrementalCompileState> parseStrict(String content) {
        List<String> lines = content.lines().toList();
        if (lines.size() < 7 || !("version=" + IncrementalCompileStateEncoding.VERSION).equals(lines.getFirst())) {
            return Optional.empty();
        }
        ScalarFields fields = new ScalarFields();
        List<String> sourceRoots = new ArrayList<>();
        List<String> generatedSourceRoots = new ArrayList<>();
        List<String> fallbackReasons = new ArrayList<>();
        List<IncrementalCompileState.ClasspathEntry> compileClasspath = new ArrayList<>();
        List<IncrementalCompileState.ClasspathEntry> processorClasspath = new ArrayList<>();
        Map<Path, SourceBuilder> sources = new LinkedHashMap<>();
        Map<String, ClassBuilder> classes = new LinkedHashMap<>();
        Map<String, List<Path>> reverseDependencies = new LinkedHashMap<>();

        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }
            if (line.startsWith("scope=")) {
                fields.scope = line.substring("scope=".length());
            } else if (line.startsWith("projectDirectory=")) {
                fields.projectDirectory = pathValue(line, "projectDirectory=");
            } else if (line.startsWith("outputDirectory=")) {
                fields.outputDirectory = pathValue(line, "outputDirectory=");
            } else if (line.startsWith("generatedSourcesDirectory=")) {
                fields.generatedSourcesDirectory = pathValue(line, "generatedSourcesDirectory=");
            } else if (line.startsWith("compilerSettingsHash=")) {
                fields.compilerSettingsHash = line.substring("compilerSettingsHash=".length());
            } else if (line.startsWith("buildFingerprintSha256=")) {
                fields.buildFingerprintSha256 = line.substring("buildFingerprintSha256=".length());
            } else if (line.startsWith("publicAbiDigest=")) {
                fields.publicAbiDigest = line.substring("publicAbiDigest=".length());
            } else if (line.startsWith("packagePrivateAbiDigest=")) {
                fields.packagePrivateAbiDigest = line.substring("packagePrivateAbiDigest=".length());
            } else if (line.startsWith("outputManifestDigest=")) {
                fields.outputManifestDigest = line.substring("outputManifestDigest=".length());
            } else if (line.startsWith("processorAttributionComplete=")) {
                fields.processorAttributionComplete =
                        Boolean.parseBoolean(line.substring("processorAttributionComplete=".length()));
            } else if (!parseRecord(line, sourceRoots, generatedSourceRoots, fallbackReasons,
                    compileClasspath, processorClasspath, sources, classes, reverseDependencies)) {
                return Optional.empty();
            }
        }

        return Optional.of(new IncrementalCompileState(
                fields.scope,
                fields.projectDirectory,
                fields.outputDirectory,
                fields.generatedSourcesDirectory,
                fields.compilerSettingsHash,
                fields.buildFingerprintSha256,
                fields.publicAbiDigest,
                fields.packagePrivateAbiDigest,
                fields.outputManifestDigest,
                fallbackReasons,
                sourceRoots,
                generatedSourceRoots,
                compileClasspath,
                processorClasspath,
                sources.values().stream().map(SourceBuilder::build).toList(),
                classes.values().stream().map(ClassBuilder::build).toList(),
                reverseDependencies,
                fields.processorAttributionComplete));
    }

    private static boolean parseRecord(
            String line,
            List<String> sourceRoots,
            List<String> generatedSourceRoots,
            List<String> fallbackReasons,
            List<IncrementalCompileState.ClasspathEntry> compileClasspath,
            List<IncrementalCompileState.ClasspathEntry> processorClasspath,
            Map<Path, SourceBuilder> sources,
            Map<String, ClassBuilder> classes,
            Map<String, List<Path>> reverseDependencies) {
        String[] parts = line.split("\t", -1);
        if (parts.length == 0) {
            return false;
        }
        switch (parts[0]) {
            case "fallbackReason" -> fallbackReasons.add(decodedPart(parts, 1, 2));
            case "sourceRoot" -> sourceRoots.add(decodedPart(parts, 1, 2));
            case "generatedSourceRoot" -> generatedSourceRoots.add(decodedPart(parts, 1, 2));
            case "compileClasspath" -> compileClasspath.add(new IncrementalCompileState.ClasspathEntry(
                    pathPart(parts, 1, 5),
                    Long.parseLong(decodedPart(parts, 2, 5)),
                    Long.parseLong(decodedPart(parts, 3, 5)),
                    decodedPart(parts, 4, 5)));
            case "processorClasspath" -> processorClasspath.add(new IncrementalCompileState.ClasspathEntry(
                    pathPart(parts, 1, 5),
                    Long.parseLong(decodedPart(parts, 2, 5)),
                    Long.parseLong(decodedPart(parts, 3, 5)),
                    decodedPart(parts, 4, 5)));
            case "source" -> source(sources, pathPart(parts, 1, 6))
                    .set(
                            pathPart(parts, 1, 6),
                            pathPart(parts, 2, 6),
                            decodedPart(parts, 3, 6),
                            decodedPart(parts, 4, 6),
                            decodedPart(parts, 5, 6));
            case "sourceDeclaredType" -> source(sources, pathPart(parts, 1, 3))
                    .declaredTypes.add(decodedPart(parts, 2, 3));
            case "sourceClass" -> source(sources, pathPart(parts, 1, 3))
                    .classOutputs.add(pathPart(parts, 2, 3));
            case "sourceReference" -> source(sources, pathPart(parts, 1, 3))
                    .referencedClasses.add(decodedPart(parts, 2, 3));
            case "sourceGeneratedSource" -> source(sources, pathPart(parts, 1, 3))
                    .generatedSources.add(pathPart(parts, 2, 3));
            case "sourceGeneratedClass" -> source(sources, pathPart(parts, 1, 3))
                    .generatedClasses.add(pathPart(parts, 2, 3));
            case "sourceGeneratedResource" -> source(sources, pathPart(parts, 1, 3))
                    .generatedResources.add(pathPart(parts, 2, 3));
            case "class" -> classes.computeIfAbsent(decodedPart(parts, 1, 8), ClassBuilder::new)
                    .set(
                            decodedPart(parts, 1, 8),
                            pathPart(parts, 2, 8),
                            decodedPart(parts, 3, 8),
                            decodedPart(parts, 4, 8),
                            decodedPart(parts, 5, 8),
                            Integer.parseInt(decodedPart(parts, 6, 8)),
                            decodedPart(parts, 7, 8));
            case "classInterface" -> classes.computeIfAbsent(decodedPart(parts, 1, 3), ClassBuilder::new)
                    .interfaces.add(decodedPart(parts, 2, 3));
            case "reverseDependency" -> reverseDependencies
                    .computeIfAbsent(decodedPart(parts, 1, 3), ignored -> new ArrayList<>())
                    .add(pathPart(parts, 2, 3));
            default -> {
                return false;
            }
        }
        return true;
    }

    private static SourceBuilder source(Map<Path, SourceBuilder> sources, Path path) {
        return sources.computeIfAbsent(path, SourceBuilder::new);
    }

    private static Path pathValue(String line, String prefix) {
        return Path.of(IncrementalCompileStateEncoding.decode(line.substring(prefix.length()))).toAbsolutePath().normalize();
    }

    private static Path pathPart(String[] parts, int index, int expectedLength) {
        return Path.of(decodedPart(parts, index, expectedLength)).toAbsolutePath().normalize();
    }

    private static String decodedPart(String[] parts, int index, int expectedLength) {
        if (parts.length != expectedLength) {
            throw new IllegalArgumentException("Unexpected incremental compile state field count.");
        }
        return IncrementalCompileStateEncoding.decode(parts[index]);
    }

    private static final class ScalarFields {
        private String scope;
        private Path projectDirectory;
        private Path outputDirectory;
        private Path generatedSourcesDirectory;
        private String compilerSettingsHash;
        private String buildFingerprintSha256;
        private String publicAbiDigest;
        private String packagePrivateAbiDigest;
        private String outputManifestDigest;
        private boolean processorAttributionComplete;
    }

    private static final class SourceBuilder {
        private final Path path;
        private Path sourceRoot;
        private Optional<String> generatedSourceStepId = Optional.empty();
        private String contentHash;
        private String packageName;
        private final List<String> declaredTypes = new ArrayList<>();
        private final List<Path> classOutputs = new ArrayList<>();
        private final List<String> referencedClasses = new ArrayList<>();
        private final List<Path> generatedSources = new ArrayList<>();
        private final List<Path> generatedClasses = new ArrayList<>();
        private final List<Path> generatedResources = new ArrayList<>();

        private SourceBuilder(Path path) {
            this.path = path;
        }

        private void set(Path path, Path sourceRoot, String generatedSourceStepId, String contentHash, String packageName) {
            if (!this.path.equals(path)) {
                throw new IllegalArgumentException("Incremental compile source path mismatch.");
            }
            this.sourceRoot = sourceRoot;
            this.generatedSourceStepId = generatedSourceStepId.isBlank()
                    ? Optional.empty()
                    : Optional.of(generatedSourceStepId);
            this.contentHash = contentHash;
            this.packageName = packageName;
        }

        private IncrementalCompileState.SourceRecord build() {
            return new IncrementalCompileState.SourceRecord(
                    path,
                    sourceRoot,
                    generatedSourceStepId,
                    contentHash,
                    packageName,
                    declaredTypes,
                    classOutputs,
                    referencedClasses,
                    generatedSources,
                    generatedClasses,
                    generatedResources);
        }
    }

    private static final class ClassBuilder {
        private final String binaryName;
        private Path outputPath;
        private String classFileHash;
        private String abiHash;
        private String packagePrivateAbiHash;
        private int accessFlags;
        private Optional<String> superName = Optional.empty();
        private final List<String> interfaces = new ArrayList<>();

        private ClassBuilder(String binaryName) {
            this.binaryName = binaryName;
        }

        private void set(
                String binaryName,
                Path outputPath,
                String classFileHash,
                String abiHash,
                String packagePrivateAbiHash,
                int accessFlags,
                String superName) {
            if (!this.binaryName.equals(binaryName)) {
                throw new IllegalArgumentException("Incremental compile class name mismatch.");
            }
            this.outputPath = outputPath;
            this.classFileHash = classFileHash;
            this.abiHash = abiHash;
            this.packagePrivateAbiHash = packagePrivateAbiHash;
            this.accessFlags = accessFlags;
            this.superName = superName.isBlank() ? Optional.empty() : Optional.of(superName);
        }

        private IncrementalCompileState.ClassRecord build() {
            return new IncrementalCompileState.ClassRecord(
                    binaryName,
                    outputPath,
                    classFileHash,
                    abiHash,
                    packagePrivateAbiHash,
                    accessFlags,
                    superName,
                    interfaces);
        }
    }
}
