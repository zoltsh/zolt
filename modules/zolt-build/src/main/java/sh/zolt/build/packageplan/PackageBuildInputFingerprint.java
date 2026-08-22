package sh.zolt.build.packageplan;

import sh.zolt.build.PackageException;
import sh.zolt.build.fingerprint.BuildFingerprintService;
import sh.zolt.build.generatedsource.GeneratedSourceProducerFingerprint;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.ProjectPaths;
import sh.zolt.project.ResourceTokenSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;

/**
 * Recomputes the live, inputs-only side of the main build for package evidence.
 *
 * <p>This intentionally mirrors the canonical main build fingerprint categories: parsed build and
 * compiler settings, discovered sources, generated-source declarations and inputs, resources and
 * effective filtering tokens, the lock resolution identity, and workspace-provider outputs.
 */
final class PackageBuildInputFingerprint {
    private PackageBuildInputFingerprint() {
    }

    static String fingerprint(
            Path projectRoot,
            ProjectConfig config,
            ZoltLockfile lockfile,
            List<PackagePlanWorkspaceInput> workspaceInputs,
            List<GeneratedSourceProducerFingerprint>
                    generatedSourceFingerprints) {
        PackageCanonicalHash hash = new PackageCanonicalHash();
        hash.value("schema", "zolt.package-build-input.v1");
        hash.value(
                "build",
                PackageBuildSettingsIdentity.main(config.build()));
        hash.value(
                "compiler",
                PackageCompilerSettingsIdentity.main(
                        config.compilerSettings()));
        hash.value("project", config.project().toString());
        hash.value("lock", lockfile.toString());
        Path applicationOutput = ProjectPaths.output(
                projectRoot,
                "[build.output].main",
                config.build().output());
        hash.value(
                "canonicalMainBuildInput",
                new BuildFingerprintService()
                        .storedMainInputsFingerprintSha256(applicationOutput));

        mainSources(projectRoot, config.build()).forEach(
                path -> file(hash, projectRoot, "source", path));
        resources(projectRoot, config.build()).forEach(
                path -> file(hash, projectRoot, "resource", path));
        generatedSourceFingerprints.stream()
                .filter(fingerprint -> "main".equals(fingerprint.scope()))
                .sorted(Comparator.comparing(
                                GeneratedSourceProducerFingerprint::stepId)
                        .thenComparing(fingerprint ->
                                fingerprint.kind().configValue()))
                .forEach(fingerprint -> {
                    hash.value(
                            "generatedProducer",
                            fingerprint.stepId()
                                    + "\t"
                                    + fingerprint.kind().configValue());
                    hash.value(
                            "generatedProducerFingerprint",
                            fingerprint.fingerprint());
                });
        generatedOutputs(projectRoot, config.build().generatedMainSources()).forEach(
                path -> file(hash, projectRoot, "generatedOutput", path));
        effectiveResourceTokens(config).forEach(
                (name, value) -> hash.value("resourceToken:" + name, value));
        workspaceInputs.forEach(input -> {
            hash.value("workspaceIdentity", input.coordinate() + "\t" + input.identity());
            hash.value("workspaceBytes", input.fingerprint());
        });
        return hash.finish();
    }

    private static List<Path> mainSources(
            Path projectRoot,
            BuildSettings build) {
        List<Path> sources = new ArrayList<>();
        for (String configuredRoot : build.sourceRoots()) {
            Path root = ProjectPaths.existingRoot(
                    projectRoot,
                    "[build].sources",
                    configuredRoot);
            sources.addAll(files(root).stream()
                    .filter(path -> path.getFileName()
                            .toString()
                            .endsWith(".java"))
                    .toList());
        }
        return sources.stream().distinct().sorted().toList();
    }

    private static List<Path> resources(Path projectRoot, BuildSettings build) {
        List<Path> resources = new ArrayList<>();
        Path mainOutput = ProjectPaths.output(projectRoot, "[build.output].main", build.output());
        Path testOutput = ProjectPaths.output(projectRoot, "[build.output].test", build.testOutput());
        for (String configuredRoot : build.resourceRoots()) {
            Path root = ProjectPaths.existingRoot(projectRoot, "[resources].main", configuredRoot);
            resources.addAll(files(root).stream()
                    .filter(path -> !path.getFileName().toString().endsWith(".java"))
                    .filter(path -> !path.startsWith(mainOutput))
                    .filter(path -> !path.startsWith(testOutput))
                    .filter(path -> !startsWithBuildDirectory(root.relativize(path)))
                    .toList());
        }
        return resources.stream().distinct().sorted().toList();
    }

    private static List<Path> generatedOutputs(
            Path projectRoot,
            List<GeneratedSourceStep> steps) {
        Set<Path> outputs = new LinkedHashSet<>();
        for (GeneratedSourceStep step : steps) {
            Path output = ProjectPaths.output(
                    projectRoot,
                    "[generated.main." + step.id() + "].output",
                    step.output());
            outputs.addAll(expand(output));
        }
        return outputs.stream().sorted().toList();
    }

    private static List<Path> expand(Path path) {
        if (Files.isRegularFile(path)) {
            return List.of(path);
        }
        return files(path);
    }

    private static List<Path> files(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try {
            return PackageInputFingerprinting.regularFiles(root);
        } catch (IOException exception) {
            throw new PackageException(
                    "Could not fingerprint package build inputs under "
                            + root
                            + ". Check that the directory is readable and retry.",
                    exception);
        }
    }

    static SortedMap<String, String> effectiveResourceTokens(ProjectConfig config) {
        Map<String, ResourceTokenSettings> configured =
                config.build().resourceFiltering().tokens();
        SortedMap<String, String> values = new java.util.TreeMap<>();
        for (Map.Entry<String, ResourceTokenSettings> entry : configured.entrySet()) {
            ResourceTokenSettings token = entry.getValue();
            String value = token.value()
                    .or(() -> token.env().map(PackageBuildInputFingerprint::environmentToken))
                    .or(() -> token.project().map(field -> projectToken(config.project(), field)))
                    .orElse("");
            values.put(entry.getKey(), value);
        }
        return Collections.unmodifiableSortedMap(values);
    }

    private static String environmentToken(String variable) {
        String value = System.getenv(variable);
        if (value == null) {
            throw new PackageException(
                    "Package build input fingerprint requires resource token environment variable `"
                            + variable
                            + "`, but it is not set.");
        }
        return value;
    }

    private static String projectToken(ProjectMetadata project, String field) {
        return switch (field) {
            case "name" -> project.name();
            case "version" -> project.version();
            case "group" -> project.group();
            case "java" -> project.java();
            case "main" -> project.main().orElse("");
            default -> throw new PackageException(
                    "Package build input fingerprint cannot resolve project resource token field `"
                            + field
                            + "`.");
        };
    }

    private static void file(
            PackageCanonicalHash hash,
            Path projectRoot,
            String kind,
            Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        hash.value(kind + "Path", display(projectRoot, normalized));
        try {
            hash.bytes(kind + "Bytes", Files.readAllBytes(normalized));
        } catch (IOException exception) {
            throw new PackageException(
                    "Could not fingerprint package build input at "
                            + normalized
                            + ". Check that the file is readable and retry.",
                    exception);
        }
    }

    private static String display(Path root, Path path) {
        return path.startsWith(root)
                ? root.relativize(path).toString().replace('\\', '/')
                : path.toString().replace('\\', '/');
    }

    private static boolean startsWithBuildDirectory(Path relative) {
        return relative.getNameCount() > 0
                && Set.of("target", "build").contains(relative.getName(0).toString());
    }
}
