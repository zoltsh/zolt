package sh.zolt.build.packaging.layout;

import sh.zolt.build.BuildResult;
import sh.zolt.build.manifest.GeneratedManifest;
import sh.zolt.build.manifest.ManifestGenerator;
import sh.zolt.build.PackageException;
import sh.zolt.build.packaging.PackageArchiveWriter;
import sh.zolt.build.packaging.PackageMergeDecision;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.build.packaging.PackageRuntimeJar;
import sh.zolt.build.packageplan.PackageInputFingerprinting;
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
        int entryCount = 0;
        try {
            boolean multiRelease = detectMultiReleaseContent(outputDirectory, runtimeJars);
            GeneratedManifest manifest = manifestGenerator.generate(projectDirectory, config, multiRelease);
            Files.createDirectories(jarPath.getParent());
            List<PackageMergeDecision> mergeDecisions = new ArrayList<>();
            try (PackageArchiveWriter archive = PackageArchiveWriter.open(jarPath)) {
                UberJarEntryWriter writer = new UberJarEntryWriter(
                        archive, new UberJarMergeAccumulator(), config.packageSettings().uberDuplicates());
                writer.writeEntry(manifest.path(), manifest.content(), "generated manifest");
                entryCount++;
                for (Path file : compiledFiles(outputDirectory)) {
                    if (writer.writeOrCollectEntry(
                            entryName(outputDirectory, file),
                            Files.readAllBytes(file),
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
            return mergeRuntimeDirectory(writer, runtimeJar);
        }
        return mergeRuntimeJar(writer, mergeDecisions, runtimeJar);
    }

    private static int mergeRuntimeDirectory(
            UberJarEntryWriter writer,
            PackageRuntimeJar runtimeJar) throws IOException {
        Path directory = runtimeJar.jarPath();
        int merged = 0;
        for (Path file : compiledFiles(directory)) {
            if (writer.writeOrCollectEntry(
                    entryName(directory, file),
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
                if (ignoredDependencyEntry(entry.getName())) {
                    if (isDependencyModuleDescriptor(entry.getName())) {
                        mergeDecisions.add(new PackageMergeDecision(
                                "omitted-module-descriptor",
                                entry.getName(),
                                java.util.Optional.empty(),
                                List.of(runtimeJar.packageId().toString())));
                    }
                    continue;
                }
                byte[] content;
                try (InputStream input = jar.getInputStream(entry)) {
                    content = input.readAllBytes();
                }
                String originalEntryName = entry.getName();
                String entryName = relocatedDependencyMetadataEntry(originalEntryName, runtimeJar);
                if (!entryName.equals(originalEntryName)) {
                    mergeDecisions.add(new PackageMergeDecision(
                            "relocated-metadata",
                            originalEntryName,
                            java.util.Optional.of(entryName),
                            List.of(runtimeJar.packageId().toString())));
                }
                if (writer.writeOrCollectEntry(entryName, content, runtimeJar.packageId().toString())) {
                    merged++;
                }
            }
        }
        return merged;
    }

    private static boolean detectMultiReleaseContent(
            Path outputDirectory,
            List<PackageRuntimeJar> runtimeJars) throws IOException {
        if (directoryHasVersionedEntry(outputDirectory)) {
            return true;
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
