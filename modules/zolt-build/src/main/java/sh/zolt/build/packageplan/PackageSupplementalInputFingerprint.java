package sh.zolt.build.packageplan;

import sh.zolt.build.PackageException;
import sh.zolt.build.fingerprint.BuildFingerprintService;
import sh.zolt.build.generatedsource.GeneratedSourceProducerFingerprint;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class PackageSupplementalInputFingerprint {
    private PackageSupplementalInputFingerprint() {
    }

    static List<PackagePlanLiveInput> inputs(
            Path projectRoot,
            ProjectConfig config,
            String buildInputFingerprint,
            String applicationOutputFingerprint,
            String packageLockFingerprint,
            List<GeneratedSourceProducerFingerprint>
                    generatedSourceFingerprints) {
        List<PackagePlanLiveInput> inputs = new ArrayList<>();
        if (config.packageSettings().sources()) {
            inputs.add(new PackagePlanLiveInput(
                    "sources",
                    sourceFingerprint(projectRoot, config.build())));
        }
        if (config.packageSettings().javadoc()) {
            PackageCanonicalHash hash = new PackageCanonicalHash();
            hash.value("schema", "zolt.package-javadoc-input.v1");
            hash.value("sources", sourceFingerprint(projectRoot, config.build()));
            hash.value("buildInput", buildInputFingerprint);
            hash.value("applicationOutput", applicationOutputFingerprint);
            hash.value("compileClasspath", packageLockFingerprint);
            inputs.add(new PackagePlanLiveInput("javadoc", hash.finish()));
        }
        if (config.packageSettings().tests()) {
            inputs.add(new PackagePlanLiveInput(
                    "tests",
                    testFingerprint(
                            projectRoot,
                            config,
                            generatedSourceFingerprints)));
        }
        return List.copyOf(inputs);
    }

    private static String sourceFingerprint(Path projectRoot, BuildSettings build) {
        PackageCanonicalHash hash = new PackageCanonicalHash();
        hash.value("schema", "zolt.package-sources-input.v1");
        for (String configuredRoot : build.sourceRoots()) {
            Path root = ProjectPaths.existingRoot(projectRoot, "[build].sources", configuredRoot);
            for (Path file : sourceFiles(root, ".java")) {
                file(hash, projectRoot, "source", file);
            }
        }
        return hash.finish();
    }

    private static String testFingerprint(
            Path projectRoot,
            ProjectConfig config,
            List<GeneratedSourceProducerFingerprint>
                    generatedSourceFingerprints) {
        BuildSettings build = config.build();
        PackageCanonicalHash hash = new PackageCanonicalHash();
        hash.value("schema", "zolt.package-tests-input.v1");
        hash.value("build", PackageBuildSettingsIdentity.test(build));
        hash.value(
                "compiler",
                PackageCompilerSettingsIdentity.test(
                        config.compilerSettings()));
        generatedSourceFingerprints.stream()
                .filter(fingerprint -> "test".equals(fingerprint.scope()))
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
        for (String configuredRoot : build.testSources()) {
            Path root = ProjectPaths.existingRoot(
                    projectRoot,
                    "[build].testSources",
                    configuredRoot);
            for (Path file : sourceFiles(root, ".java")) {
                file(hash, projectRoot, "testSource", file);
            }
        }
        for (String configuredRoot : build.groovyTestSources()) {
            Path root = ProjectPaths.existingRoot(
                    projectRoot,
                    "[build].groovyTestSources",
                    configuredRoot);
            for (Path file : sourceFiles(root, ".groovy")) {
                file(hash, projectRoot, "groovyTestSource", file);
            }
        }
        for (GeneratedSourceStep step : build.generatedTestSources().stream()
                .sorted(Comparator.comparing(GeneratedSourceStep::id)
                        .thenComparing(value ->
                                value.kind().configValue()))
                .toList()) {
            Path output = ProjectPaths.output(
                    projectRoot,
                    "[generated.test." + step.id() + "].output",
                    step.output());
            for (Path file : regularFiles(output)) {
                file(hash, projectRoot, "generatedTest", file);
            }
        }
        for (String configuredRoot : build.testResourceRoots()) {
            Path root = ProjectPaths.existingRoot(
                    projectRoot,
                    "[resources].test",
                    configuredRoot);
            for (Path file : regularFiles(root)) {
                file(hash, projectRoot, "testResource", file);
            }
        }
        Path testOutput = ProjectPaths.output(
                projectRoot,
                "[build].testOutput",
                build.testOutput());
        hash.value(
                "testCompileInputs",
                new BuildFingerprintService()
                        .storedTestInputsFingerprintSha256(testOutput));
        hash.value(
                "testOutput",
                PackageInputFingerprinting.applicationOutputFingerprint(testOutput));
        return hash.finish();
    }

    private static List<Path> sourceFiles(Path root, String extension) {
        return regularFiles(root).stream()
                .filter(path -> path.getFileName().toString().endsWith(extension))
                .toList();
    }

    private static List<Path> regularFiles(Path root) {
        try {
            return PackageInputFingerprinting.regularFiles(root);
        } catch (IOException exception) {
            throw new PackageException(
                    "Could not fingerprint supplemental package inputs under "
                            + root
                            + ". Check that the directory is readable and retry.",
                    exception);
        }
    }

    private static void file(
            PackageCanonicalHash hash,
            Path root,
            String kind,
            Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        hash.value(
                kind + "Path",
                normalized.startsWith(root)
                        ? root.relativize(normalized).toString().replace('\\', '/')
                        : normalized.toString().replace('\\', '/'));
        try {
            hash.bytes(
                    kind + "Bytes",
                    Files.readAllBytes(normalized));
        } catch (IOException exception) {
            throw new PackageException(
                    "Could not fingerprint supplemental package input at "
                            + normalized
                            + ". Check that it is readable and retry.",
                    exception);
        }
    }
}
