package sh.zolt.build.packaging.layout;

import sh.zolt.build.BuildResult;
import sh.zolt.build.manifest.GeneratedManifest;
import sh.zolt.build.manifest.ManifestGenerator;
import sh.zolt.build.PackageException;
import sh.zolt.build.packaging.PackageArchiveWriter;
import sh.zolt.build.packaging.PackageMergeDecision;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.build.packaging.PackageRuntimeJar;
import sh.zolt.build.packageevidence.PackageArchiveDigests;
import sh.zolt.build.packageplan.PackageInputFingerprinting;
import sh.zolt.build.packageplan.PackageInputBudget;
import sh.zolt.build.packageplan.PackageInputEntry;
import sh.zolt.build.packageplan.PackageInputSnapshot;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

public final class UberJarLayoutAssembler {
    private static final Pattern VERSIONED_ENTRY = Pattern.compile("META-INF/versions/[0-9]+/.+");

    private final ManifestGenerator manifestGenerator;

    public UberJarLayoutAssembler(ManifestGenerator manifestGenerator) {
        this.manifestGenerator = manifestGenerator;
    }

    public PackageResult assemble(
            Path projectDirectory,
            ProjectConfig config,
            BuildResult buildResult,
            Path outputDirectory,
            Path jarPath,
            List<PackageRuntimeJar> runtimeJars) {
        return assemble(
                projectDirectory,
                config,
                buildResult,
                outputDirectory,
                jarPath,
                runtimeJars,
                PackageInputSnapshot.of(outputDirectory, PackageInputBudget.defaults()),
                new PackageArchiveDigests());
    }

    public PackageResult assemble(
            Path projectDirectory,
            ProjectConfig config,
            BuildResult buildResult,
            Path outputDirectory,
            Path jarPath,
            List<PackageRuntimeJar> runtimeJars,
            PackageInputSnapshot inputs,
            PackageArchiveDigests digests) {
        int entryCount = 0;
        try {
            boolean multiRelease = detectMultiReleaseContent(inputs, runtimeJars);
            GeneratedManifest manifest = manifestGenerator.generate(projectDirectory, config, multiRelease);
            Files.createDirectories(jarPath.getParent());
            List<PackageMergeDecision> mergeDecisions = new ArrayList<>();
            try (PackageArchiveWriter archive = PackageArchiveWriter.open(jarPath)) {
                UberJarEntryWriter writer = new UberJarEntryWriter(
                        archive, new UberJarMergeAccumulator(), config.packageSettings().uberDuplicates());
                writer.writeEntry(manifest.path(), manifest.content(), "generated manifest");
                entryCount++;
                for (PackageInputEntry file : inputs.entries()) {
                    if (writer.writeOrCollectEntry(
                            file.name(),
                            inputs.content(file),
                            "application output")) {
                        entryCount++;
                    }
                }
                for (PackageRuntimeJar runtimeJar : runtimeJars) {
                    entryCount += mergeRuntimeInput(writer, mergeDecisions, runtimeJar);
                }
                entryCount += writer.writeMergedEntries();
                mergeDecisions.addAll(writer.decisions());
                archive.commit();
                archive.archiveSha256().ifPresent(sha256 -> digests.record(jarPath, sha256));
            }
            return new PackageResult(
                    buildResult,
                    PackageMode.UBER,
                    jarPath,
                    java.util.Optional.empty(),
                    entryCount - 1,
                    manifest.mainClass().isPresent())
                    .withMergeDecisions(mergeDecisions);
        } catch (IOException exception) {
            throw new PackageException(
                    "Could not package uber jar at "
                            + jarPath
                            + ". Check that target/ is writable and try again.",
                    exception);
        }
    }

    private static int mergeRuntimeInput(
            UberJarEntryWriter writer,
            List<PackageMergeDecision> mergeDecisions,
            PackageRuntimeJar runtimeJar) throws IOException {
        if (Files.isDirectory(runtimeJar.jarPath())) {
            return mergeRuntimeDirectory(writer, mergeDecisions, runtimeJar);
        }
        return mergeRuntimeJar(writer, mergeDecisions, runtimeJar);
    }

    private static int mergeRuntimeDirectory(
            UberJarEntryWriter writer,
            List<PackageMergeDecision> mergeDecisions,
            PackageRuntimeJar runtimeJar) throws IOException {
        Path directory = runtimeJar.jarPath();
        int merged = 0;
        for (Path file : compiledFiles(directory)) {
            Optional<String> name = dependencyEntryName(
                    entryName(directory, file), runtimeJar, mergeDecisions);
            if (name.isEmpty()) {
                continue;
            }
            if (writer.writeOrCollectEntry(
                    name.orElseThrow(),
                    Files.readAllBytes(file),
                    runtimeJar.packageId().toString())) {
                merged++;
            }
        }
        return merged;
    }

    private static int mergeRuntimeJar(
            UberJarEntryWriter writer,
            List<PackageMergeDecision> mergeDecisions,
            PackageRuntimeJar runtimeJar) throws IOException {
        int merged = 0;
        try (JarFile jar = new JarFile(runtimeJar.jarPath().toFile())) {
            List<java.util.jar.JarEntry> jarEntries = jar.stream()
                    .filter(entry -> !entry.isDirectory())
                    .sorted(Comparator.comparing(java.util.jar.JarEntry::getName))
                    .toList();
            for (java.util.jar.JarEntry entry : jarEntries) {
                Optional<String> name = dependencyEntryName(
                        entry.getName(), runtimeJar, mergeDecisions);
                if (name.isEmpty()) {
                    continue;
                }
                byte[] content;
                try (InputStream input = jar.getInputStream(entry)) {
                    content = input.readAllBytes();
                }
                if (writer.writeOrCollectEntry(name.orElseThrow(), content, runtimeJar.packageId().toString())) {
                    merged++;
                }
            }
        }
        return merged;
    }

    /** Directory-backed workspace dependencies obey the same merge policy as Maven JARs. */
    private static Optional<String> dependencyEntryName(
            String name,
            PackageRuntimeJar runtimeJar,
            List<PackageMergeDecision> mergeDecisions) {
        if (ignoredDependencyEntry(name)) {
            if (isDependencyModuleDescriptor(name)) {
                mergeDecisions.add(new PackageMergeDecision(
                        "omitted-module-descriptor", name, Optional.empty(),
                        List.of(runtimeJar.packageId().toString())));
            }
            return Optional.empty();
        }
        String relocated = relocatedDependencyMetadataEntry(name, runtimeJar);
        if (!relocated.equals(name)) {
            mergeDecisions.add(new PackageMergeDecision(
                    "relocated-metadata", name, Optional.of(relocated),
                    List.of(runtimeJar.packageId().toString())));
        }
        return Optional.of(relocated);
    }

    private static boolean detectMultiReleaseContent(
            PackageInputSnapshot inputs,
            List<PackageRuntimeJar> runtimeJars) throws IOException {
        for (PackageInputEntry entry : inputs.entries()) {
            if (isMultiReleaseEntry(entry.name())) {
                return true;
            }
        }
        for (PackageRuntimeJar runtimeJar : runtimeJars) {
            if (Files.isDirectory(runtimeJar.jarPath())) {
                if (directoryHasVersionedEntry(runtimeJar.jarPath())) {
                    return true;
                }
            } else if (jarHasVersionedEntry(runtimeJar.jarPath())) {
                return true;
            }
        }
        return false;
    }

    private static boolean directoryHasVersionedEntry(Path directory) throws IOException {
        for (Path file : compiledFiles(directory)) {
            if (isMultiReleaseEntry(entryName(directory, file))) {
                return true;
            }
        }
        return false;
    }

    private static boolean jarHasVersionedEntry(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            return jar.stream()
                    .filter(entry -> !entry.isDirectory())
                    .map(java.util.jar.JarEntry::getName)
                    .anyMatch(UberJarLayoutAssembler::isMultiReleaseEntry);
        }
    }

    private static boolean isMultiReleaseEntry(String name) {
        return VERSIONED_ENTRY.matcher(name).matches() && !ignoredDependencyEntry(name);
    }

    private static boolean ignoredDependencyEntry(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if ("META-INF/MANIFEST.MF".equals(upper)
                || "MODULE-INFO.CLASS".equals(upper)
                || upper.matches("META-INF/VERSIONS/[0-9]+/MODULE-INFO\\.CLASS")) {
            return true;
        }
        if (!upper.startsWith("META-INF/")) {
            return false;
        }
        return upper.endsWith(".SF")
                || upper.endsWith(".DSA")
                || upper.endsWith(".RSA")
                || upper.endsWith(".EC")
                || "META-INF/INDEX.LIST".equals(upper);
    }

    private static boolean isDependencyModuleDescriptor(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return "MODULE-INFO.CLASS".equals(upper)
                || upper.matches("META-INF/VERSIONS/[0-9]+/MODULE-INFO\\.CLASS");
    }

    private static String relocatedDependencyMetadataEntry(String name, PackageRuntimeJar runtimeJar) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) {
            return name;
        }
        String fileName = name.substring(name.lastIndexOf('/') + 1);
        String upperFileName = fileName.toUpperCase(Locale.ROOT);
        if (!(upperFileName.startsWith("LICENSE")
                || upperFileName.startsWith("NOTICE")
                || upperFileName.startsWith("DEPENDENCIES"))) {
            return name;
        }
        return "META-INF/zolt-uber/"
                + runtimeJar.packageId().groupId().replace('.', '/')
                + "/"
                + runtimeJar.packageId().artifactId()
                + "/"
                + runtimeJar.version()
                + "/"
                + fileName;
    }

    private static List<Path> compiledFiles(Path outputDirectory) throws IOException {
        return PackageInputFingerprinting.applicationFiles(outputDirectory);
    }

    private static String entryName(Path outputDirectory, Path file) {
        return outputDirectory.relativize(file).normalize().toString().replace('\\', '/');
    }
}
