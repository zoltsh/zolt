package sh.zolt.build.nativeimage;

import sh.zolt.build.NativeImageException;
import sh.zolt.build.packageevidence.PackageEvidenceManifestWriter;
import sh.zolt.build.packaging.PackageArtifactPathPlanner;
import sh.zolt.build.packageplan.PackagePlanOutputs;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every path owned by one native build, validated before that build mutates the project. */
public record NativeOutputPlan(
        Path binary,
        Path log,
        Path evidence,
        Path inputDirectory,
        List<Path> configuredPackageOutputs) {
    private static final String LOG_NAME = "native-image.log";
    private static final String EVIDENCE_NAME = "spring-aot-evidence.json";
    private static final String INPUT_NAME = "input";

    public NativeOutputPlan {
        configuredPackageOutputs = List.copyOf(configuredPackageOutputs);
    }

    public static NativeOutputPlan plan(Path projectDirectory, ProjectConfig config) {
        Path root = ProjectPaths.root(projectDirectory);
        String output = config.nativeSettings().output();
        ProjectPaths.output(root, "[native].output", output);
        String imageName = ProjectPaths.filenameComponent(
                "[native].imageName",
                config.nativeSettings().withDefaultImageName(config.project().name()).imageName());
        NativeOutputPlan plan = new NativeOutputPlan(
                ProjectPaths.output(root, "native binary", output + "/" + imageName),
                ProjectPaths.output(root, "native log", output + "/" + LOG_NAME),
                ProjectPaths.output(root, "Spring Boot native evidence", output + "/" + EVIDENCE_NAME),
                ProjectPaths.output(root, "native package input", output + "/" + INPUT_NAME),
                configuredPackageOutputs(root, config));
        plan.validate();
        return plan;
    }

    private void validate() {
        Map<String, Path> nativeOutputs = new LinkedHashMap<>();
        nativeOutputs.put("native binary", binary);
        nativeOutputs.put("native log", log);
        nativeOutputs.put("Spring Boot native evidence", evidence);
        nativeOutputs.put("native package input", inputDirectory);
        requirePairwiseDistinct(nativeOutputs);

        Path ownedBinary = ownershipPath(binary);
        Path ownedInput = ownershipPath(inputDirectory);
        if (ownedBinary.startsWith(ownedInput)) {
            throw conflict("native binary", binary, "native package input", inputDirectory);
        }

        for (Path configured : configuredPackageOutputs) {
            Path ownedConfigured = ownershipPath(configured);
            rejectSame("native binary", binary, ownedBinary, configured, ownedConfigured);
            rejectSame("native log", log, ownershipPath(log), configured, ownedConfigured);
            rejectSame("Spring Boot native evidence", evidence, ownershipPath(evidence), configured, ownedConfigured);
            if (ownedConfigured.startsWith(ownedInput) || ownedInput.startsWith(ownedConfigured)) {
                throw conflict(
                        "native package input",
                        inputDirectory,
                        "configured package output",
                        configured);
            }
        }
    }

    private static void requirePairwiseDistinct(Map<String, Path> paths) {
        List<Map.Entry<String, Path>> entries = new ArrayList<>(paths.entrySet());
        for (int left = 0; left < entries.size(); left++) {
            for (int right = left + 1; right < entries.size(); right++) {
                var first = entries.get(left);
                var second = entries.get(right);
                if (ownershipPath(first.getValue()).equals(ownershipPath(second.getValue()))) {
                    throw conflict(first.getKey(), first.getValue(), second.getKey(), second.getValue());
                }
            }
        }
    }

    private static void rejectSame(
            String nativeKind,
            Path nativePath,
            Path ownedNative,
            Path configured,
            Path ownedConfigured) {
        if (ownedNative.equals(ownedConfigured)
                || ownedNative.startsWith(ownedConfigured)
                || ownedConfigured.startsWith(ownedNative)) {
            throw conflict(nativeKind, nativePath, "configured package output", configured);
        }
    }

    private static NativeImageException conflict(
            String firstKind,
            Path first,
            String secondKind,
            Path second) {
        return new NativeImageException(
                "Native output ownership conflict: "
                        + firstKind
                        + " `"
                        + first
                        + "` overlaps "
                        + secondKind
                        + " `"
                        + second
                        + "`. Choose distinct [native].output and [native].imageName values that do not overlap "
                        + "configured package outputs.");
    }

    /** Resolves existing symlink ancestors so lexically different paths cannot claim one destination. */
    private static Path ownershipPath(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        try {
            return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
        } catch (IOException exception) {
            throw new NativeImageException(
                    "Could not validate Native Image output ownership at "
                            + path
                            + ". Check that the path and its ancestors are readable.",
                    exception);
        }
    }

    private static List<Path> configuredPackageOutputs(Path root, ProjectConfig config) {
        PackageArtifactPathPlanner artifacts = new PackageArtifactPathPlanner();
        String baseName = artifacts.artifactBaseName(config);
        Path archive = configuredArchive(root, config, artifacts, baseName);
        List<Path> outputs = new ArrayList<>();
        outputs.add(archive);
        outputs.add(PackageEvidenceManifestWriter.evidenceManifestPath(archive));
        if (config.packageSettings().mode() == PackageMode.THIN) {
            outputs.add(ProjectPaths.output(
                    root,
                    "package runtime classpath",
                    config.build().outputRoot() + "/" + baseName + ".runtime-classpath"));
        }
        if (config.packageSettings().sources()) {
            outputs.add(PackagePlanOutputs.classifierJarPath(root, config, "sources"));
        }
        if (config.packageSettings().javadoc()) {
            outputs.add(PackagePlanOutputs.classifierJarPath(root, config, "javadoc"));
        }
        if (config.packageSettings().tests()) {
            outputs.add(PackagePlanOutputs.classifierJarPath(root, config, "tests"));
        }
        outputs.add(ProjectPaths.output(
                root,
                "publish POM",
                config.build().outputRoot() + "/publish/" + baseName + ".pom"));
        return outputs.stream().distinct().toList();
    }

    private static Path configuredArchive(
            Path root,
            ProjectConfig config,
            PackageArtifactPathPlanner artifacts,
            String baseName) {
        return switch (config.packageSettings().mode()) {
            case WAR, SPRING_BOOT_WAR -> artifacts.archivePath(root, config, "war");
            case BOM -> ProjectPaths.output(
                    root,
                    "BOM package artifact",
                    config.build().outputRoot() + "/publish/" + baseName + ".pom");
            default -> artifacts.archivePath(root, config, "jar");
        };
    }
}
