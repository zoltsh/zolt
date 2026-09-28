package sh.zolt.build.compile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import sh.zolt.build.GroovyCompileException;
import sh.zolt.build.lockfile.VerifiedArtifactHashes;
import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.classpath.ResolvedPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;

/** Selects and validates the locked artifact allowed to bootstrap Groovy compilation. */
public final class GroovyCompilerToolchainResolver {
    private static final PackageId GROOVY_PACKAGE = new PackageId("org.apache.groovy", "groovy");
    private static final String COMPILER_ENTRY = "org/codehaus/groovy/tools/FileSystemCompiler.class";
    private static final String RELEASE_INFO = "META-INF/groovy-release-info.properties";
    private static final String RELEASE_INFO_VERSION = "ImplementationVersion";

    public GroovyCompilerToolchain resolve(
            List<ResolvedClasspathPackage> packages,
            SourceSet sourceSet) {
        Objects.requireNonNull(sourceSet, "Groovy compiler source set is required.");
        List<ResolvedClasspathPackage> all = packages == null ? List.of() : List.copyOf(packages);
        List<ResolvedClasspathPackage> coordinateMatches = all.stream()
                .filter(dependency -> dependency.resolvedPackage().packageId().equals(GROOVY_PACKAGE))
                .toList();
        List<ResolvedClasspathPackage> visibleMatches = coordinateMatches.stream()
                .filter(dependency -> sourceSet.visible(dependency.scope()))
                .toList();
        List<ResolvedClasspathPackage> directMatches = visibleMatches.stream()
                .filter(dependency -> dependency.resolvedPackage().direct())
                .sorted(Comparator.comparing(GroovyCompilerToolchainResolver::sortKey))
                .toList();

        if (directMatches.isEmpty()) {
            throw missing(sourceSet, coordinateMatches, visibleMatches);
        }

        List<Candidate> candidates = new ArrayList<>();
        for (ResolvedClasspathPackage dependency : directMatches) {
            candidates.add(candidate(dependency, sourceSet));
        }
        List<Candidate> distinct = candidates.stream().distinct().toList();
        if (distinct.size() != 1) {
            String selections = distinct.stream()
                    .map(candidate -> candidate.version() + " at " + candidate.jar())
                    .sorted()
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("none");
            throw new GroovyCompileException(
                    "Groovy " + sourceSet.label + " compilation found ambiguous direct "
                            + GroovyCompilerToolchain.COORDINATE + " compiler artifacts: " + selections + ". "
                            + "Keep one direct default Groovy JAR version in " + sourceSet.dependencySections
                            + ", run `zolt resolve`, and retry.");
        }

        Candidate selected = distinct.getFirst();
        String hashBefore = verifiedHash(selected.jar(), sourceSet);
        inspectJar(selected, sourceSet);
        String hashAfter = verifiedHash(selected.jar(), sourceSet);
        if (!hashBefore.equals(hashAfter)) {
            throw invalid(
                    sourceSet,
                    "the verified artifact identity changed while its JAR was being inspected");
        }
        return new GroovyCompilerToolchain(selected.version(), hashAfter, selected.jar());
    }

    private static Candidate candidate(
            ResolvedClasspathPackage dependency,
            SourceSet sourceSet) {
        ResolvedPackage resolved = dependency.resolvedPackage();
        NestedArtifactIdentity identity = resolved.artifactIdentity();
        if (identity.sourceKind() != NestedArtifactIdentity.SourceKind.EXTERNAL) {
            throw invalid(sourceSet, "a workspace substitution was selected for "
                    + GroovyCompilerToolchain.COORDINATE);
        }
        if (!identity.packageId().equals(GROOVY_PACKAGE)
                || !identity.version().equals(resolved.selectedVersion())) {
            throw invalid(sourceSet, "the resolved package and artifact identities disagree for "
                    + GroovyCompilerToolchain.COORDINATE);
        }
        if (!"jar".equals(identity.extension()) || identity.classifier().isPresent()) {
            throw invalid(sourceSet, GroovyCompilerToolchain.COORDINATE
                    + " is not the default unclassified JAR variant");
        }
        return new Candidate(
                resolved.selectedVersion(),
                resolved.jarPath().toAbsolutePath().normalize());
    }

    private static String verifiedHash(Path jar, SourceSet sourceSet) {
        if (!Files.isRegularFile(jar)) {
            throw invalid(sourceSet, "the selected compiler JAR is not a regular file at " + jar);
        }
        return VerifiedArtifactHashes.currentHash(jar).orElseThrow(() -> invalid(
                sourceSet,
                "the selected compiler JAR at " + jar
                        + " has no current checksum-verified artifact identity"));
    }

    private static void inspectJar(Candidate candidate, SourceSet sourceSet) {
        try (JarFile jar = new JarFile(candidate.jar().toFile(), false)) {
            if (jar.getJarEntry(COMPILER_ENTRY) == null) {
                throw invalid(sourceSet, "the selected compiler JAR does not contain " + COMPILER_ENTRY);
            }
            List<VersionMetadata> versions = versionMetadata(jar);
            if (versions.isEmpty()) {
                throw invalid(sourceSet, "the selected compiler JAR has no Groovy implementation version metadata");
            }
            for (VersionMetadata metadata : versions) {
                if (!candidate.version().equals(metadata.version())) {
                    throw invalid(
                            sourceSet,
                            "the selected compiler JAR reports " + metadata.label() + " `"
                                    + metadata.version() + "` but zolt.lock selected `" + candidate.version() + "`");
                }
            }
        } catch (GroovyCompileException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new GroovyCompileException(
                    "Could not inspect the checksum-verified Groovy compiler JAR at " + candidate.jar()
                            + ". Run `zolt resolve` to refresh the artifact cache, then retry.",
                    exception);
        }
    }

    private static List<VersionMetadata> versionMetadata(JarFile jar) throws IOException {
        List<VersionMetadata> versions = new ArrayList<>();
        if (jar.getManifest() != null) {
            String implementationVersion = normalize(jar.getManifest()
                    .getMainAttributes()
                    .getValue(Attributes.Name.IMPLEMENTATION_VERSION));
            if (!implementationVersion.isEmpty()) {
                versions.add(new VersionMetadata("Implementation-Version", implementationVersion));
            }
        }
        JarEntry releaseInfo = jar.getJarEntry(RELEASE_INFO);
        if (releaseInfo != null) {
            Properties properties = new Properties();
            try (InputStream input = jar.getInputStream(releaseInfo)) {
                properties.load(input);
            }
            String implementationVersion = normalize(properties.getProperty(RELEASE_INFO_VERSION));
            if (!implementationVersion.isEmpty()) {
                versions.add(new VersionMetadata(RELEASE_INFO_VERSION, implementationVersion));
            }
        }
        return List.copyOf(versions);
    }

    private static GroovyCompileException missing(
            SourceSet sourceSet,
            List<ResolvedClasspathPackage> coordinateMatches,
            List<ResolvedClasspathPackage> visibleMatches) {
        String reason;
        if (!visibleMatches.isEmpty()) {
            reason = "is present only transitively";
        } else if (!coordinateMatches.isEmpty()) {
            reason = "is not visible to the " + sourceSet.label + " compile source set";
        } else {
            reason = "is not present in the verified resolved packages";
        }
        return new GroovyCompileException(
                "Groovy " + sourceSet.label + " compilation requires a direct external default JAR for "
                        + GroovyCompilerToolchain.COORDINATE + ", but it " + reason + ". Declare it in "
                        + sourceSet.dependencySections + ", run `zolt resolve`, and retry.");
    }

    private static GroovyCompileException invalid(SourceSet sourceSet, String reason) {
        return new GroovyCompileException(
                "Groovy " + sourceSet.label + " compiler toolchain is invalid because " + reason + ". "
                        + "Use a direct external default " + GroovyCompilerToolchain.COORDINATE + " JAR in "
                        + sourceSet.dependencySections + ", run `zolt resolve`, and retry.");
    }

    private static String sortKey(ResolvedClasspathPackage dependency) {
        ResolvedPackage resolved = dependency.resolvedPackage();
        return resolved.selectedVersion() + "\u0000" + resolved.jarPath().toAbsolutePath().normalize()
                + "\u0000" + dependency.scope().lockfileName();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private record Candidate(String version, Path jar) {
    }

    private record VersionMetadata(String label, String version) {
    }

    /** The dependency visibility contract used to acquire a compiler for one source set. */
    public enum SourceSet {
        MAIN("main", "[dependencies]"),
        TEST("test", "[dependencies] or [dependencies.test]");

        private final String label;
        private final String dependencySections;

        SourceSet(String label, String dependencySections) {
            this.label = label;
            this.dependencySections = dependencySections;
        }

        private boolean visible(DependencyScope scope) {
            return this == MAIN
                    ? scope.entersMainCompileClasspath()
                    : scope.entersTestCompileClasspath();
        }
    }
}
