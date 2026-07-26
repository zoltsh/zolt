package sh.zolt.build.fingerprint;

import sh.zolt.build.discovery.SourceDiscoveryResult;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectConfig;
import sh.zolt.build.BuildException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class BuildFingerprintService {
    private static final String MAIN_FILE_NAME = ".zolt-build-main.fingerprint";
    private static final String TEST_FILE_NAME = ".zolt-build-test.fingerprint";
    private final BuildFingerprintEngine engine = new BuildFingerprintEngine();

    public boolean isMainCompileCurrent(
            Path projectDirectory,
            ProjectConfig config,
            Path lockfilePath,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            Path outputDirectory,
            Path generatedSourcesDirectory) {
        return checkMainCompileCurrent(
                projectDirectory,
                config,
                lockfilePath,
                sources,
                classpaths,
                outputDirectory,
                generatedSourcesDirectory).current();
    }

    public BuildFingerprintCheck checkMainCompileCurrent(
            Path projectDirectory,
            ProjectConfig config,
            Path lockfilePath,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            Path outputDirectory,
            Path generatedSourcesDirectory) {
        return engine.checkCompileCurrent(
                projectDirectory,
                config,
                lockfilePath,
                mainSourceRoots(config.build()),
                config.build().resourceRoots(),
                "[resources].main",
                sources.mainSources(),
                config.build().generatedMainSources(),
                classpaths.compile(),
                classpaths.processor(),
                outputDirectory,
                config.build().output(),
                generatedSourcesDirectory,
                MAIN_FILE_NAME);
    }

    public void writeMainCompileFingerprint(
            Path projectDirectory,
            ProjectConfig config,
            Path lockfilePath,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            Path outputDirectory,
            Path generatedSourcesDirectory) {
        engine.writeCompileFingerprint(
                projectDirectory,
                config,
                lockfilePath,
                mainSourceRoots(config.build()),
                config.build().resourceRoots(),
                "[resources].main",
                sources.mainSources(),
                config.build().generatedMainSources(),
                classpaths.compile(),
                classpaths.processor(),
                outputDirectory,
                config.build().output(),
                generatedSourcesDirectory,
                MAIN_FILE_NAME);
    }

    public boolean isTestCompileCurrent(
            Path projectDirectory,
            ProjectConfig config,
            Path lockfilePath,
            SourceDiscoveryResult sources,
            Classpath compileClasspath,
            Classpath processorClasspath,
            Path outputDirectory,
            Path generatedSourcesDirectory) {
        return checkTestCompileCurrent(
                projectDirectory,
                config,
                lockfilePath,
                sources,
                compileClasspath,
                processorClasspath,
                outputDirectory,
                generatedSourcesDirectory).current();
    }

    public BuildFingerprintCheck checkTestCompileCurrent(
            Path projectDirectory,
            ProjectConfig config,
            Path lockfilePath,
            SourceDiscoveryResult sources,
            Classpath compileClasspath,
            Classpath processorClasspath,
            Path outputDirectory,
            Path generatedSourcesDirectory) {
        return engine.checkCompileCurrent(
                projectDirectory,
                config,
                lockfilePath,
                testSourceRoots(config.build()),
                config.build().testResourceRoots(),
                "[resources].test",
                sources.allTestSources(),
                config.build().generatedTestSources(),
                compileClasspath,
                processorClasspath,
                outputDirectory,
                config.build().testOutput(),
                generatedSourcesDirectory,
                TEST_FILE_NAME);
    }

    public void writeTestCompileFingerprint(
            Path projectDirectory,
            ProjectConfig config,
            Path lockfilePath,
            SourceDiscoveryResult sources,
            Classpath compileClasspath,
            Classpath processorClasspath,
            Path outputDirectory,
            Path generatedSourcesDirectory) {
        engine.writeCompileFingerprint(
                projectDirectory,
                config,
                lockfilePath,
                testSourceRoots(config.build()),
                config.build().testResourceRoots(),
                "[resources].test",
                sources.allTestSources(),
                config.build().generatedTestSources(),
                compileClasspath,
                processorClasspath,
                outputDirectory,
                config.build().testOutput(),
                generatedSourcesDirectory,
                TEST_FILE_NAME);
    }

    /**
     * The inputs-only fingerprint SHA-256 for the main compile scope: the content component of a
     * build-output cache key. Computed from the same input sections the skip-gate hashes, minus the
     * {@code [expectedClasses]} output section. Stable across the compile it keys (inputs do not change
     * while javac runs), so the value taken before a compile matches the one implied after it.
     */
    public String mainInputsFingerprintSha256(
            Path projectDirectory,
            ProjectConfig config,
            Path lockfilePath,
            SourceDiscoveryResult sources,
            ClasspathSet classpaths,
            Path outputDirectory,
            Path generatedSourcesDirectory) {
        return engine.inputsFingerprintSha256(
                projectDirectory,
                config,
                lockfilePath,
                mainSourceRoots(config.build()),
                config.build().resourceRoots(),
                "[resources].main",
                sources.mainSources(),
                config.build().generatedMainSources(),
                classpaths.compile(),
                classpaths.processor(),
                outputDirectory,
                config.build().output(),
                generatedSourcesDirectory);
    }

    /**
     * Reads the canonical inputs-only fingerprint that produced the current main output.
     *
     * <p>Package evidence combines this compiler-owned identity with a live source/resource check,
     * so it reuses the exact build fingerprint model while still detecting edits made after the last
     * compile.
     */
    public String storedMainInputsFingerprintSha256(Path outputDirectory) {
        Path fingerprintPath = outputDirectory
                .toAbsolutePath()
                .normalize()
                .resolve(MAIN_FILE_NAME);
        if (!Files.isRegularFile(fingerprintPath)) {
            return "missing";
        }
        try {
            return "sha256:"
                    + BuildFingerprintInputs.inputsSha256(
                            Files.readString(fingerprintPath));
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not read the canonical main build input fingerprint at "
                            + fingerprintPath
                            + ". Run `zolt build` to refresh it.",
                    exception);
        }
    }

    /** The inputs-only fingerprint SHA-256 for the test compile scope. See {@link #mainInputsFingerprintSha256}. */
    public String testInputsFingerprintSha256(
            Path projectDirectory,
            ProjectConfig config,
            Path lockfilePath,
            SourceDiscoveryResult sources,
            Classpath compileClasspath,
            Classpath processorClasspath,
            Path outputDirectory,
            Path generatedSourcesDirectory) {
        return engine.inputsFingerprintSha256(
                projectDirectory,
                config,
                lockfilePath,
                testSourceRoots(config.build()),
                config.build().testResourceRoots(),
                "[resources].test",
                sources.allTestSources(),
                config.build().generatedTestSources(),
                compileClasspath,
                processorClasspath,
                outputDirectory,
                config.build().testOutput(),
                generatedSourcesDirectory);
    }

    private static List<String> testSourceRoots(BuildSettings settings) {
        List<String> roots = new ArrayList<>();
        roots.addAll(settings.testSources());
        roots.addAll(settings.generatedTestSources().stream()
                .map(GeneratedSourceStep::output)
                .toList());
        roots.addAll(settings.groovyTestSources());
        return List.copyOf(roots);
    }

    private static List<String> mainSourceRoots(BuildSettings settings) {
        List<String> roots = new ArrayList<>();
        roots.addAll(settings.sourceRoots());
        roots.addAll(settings.generatedMainSources().stream()
                .map(GeneratedSourceStep::output)
                .toList());
        return List.copyOf(roots);
    }
}
