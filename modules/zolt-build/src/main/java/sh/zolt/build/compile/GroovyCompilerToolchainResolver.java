package sh.zolt.build.compile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * Resolves either the legacy dependency-provided compiler or an explicitly configured compiler
     * toolchain. A blank configured version preserves the compatibility behavior of
     * {@link #resolve(List, SourceSet)}.
     */
    public GroovyCompilerToolchain resolve(
            List<ResolvedClasspathPackage> packages,
            SourceSet sourceSet,
            String configuredVersion) {
        String version = normalize(configuredVersion);
        if (version.isEmpty()) {
            return resolve(packages, sourceSet);
        }
        return resolveExplicit(packages, sourceSet, version);
    }

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

    private GroovyCompilerToolchain resolveExplicit(
            List<ResolvedClasspathPackage> packages,
            SourceSet sourceSet,
            String configuredVersion) {
        Objects.requireNonNull(sourceSet, "Groovy compiler source set is required.");
        List<ResolvedClasspathPackage> all = packages == null ? List.of() : List.copyOf(packages);
        List<ResolvedClasspathPackage> toolClosure = all.stream()
                .filter(dependency -> dependency.scope() == DependencyScope.TOOL_GROOVY)
                .toList();
        List<ResolvedClasspathPackage> directRoots = toolClosure.stream()
                .filter(dependency -> dependency.resolvedPackage().direct())
                .toList();
        List<ResolvedClasspathPackage> groovyRoots = directRoots.stream()
                .filter(dependency -> dependency.resolvedPackage().packageId().equals(GROOVY_PACKAGE))
                .toList();

        if (groovyRoots.isEmpty()) {
            throw explicitInvalid(
                    sourceSet,
                    "zolt.lock has no direct " + GroovyCompilerToolchain.COORDINATE
                            + " root in scope `tool-groovy` for configured version `"
                            + configuredVersion + "`");
        }
        if (groovyRoots.size() > 1) {
            throw explicitInvalid(
                    sourceSet,
                    "zolt.lock has ambiguous direct " + GroovyCompilerToolchain.COORDINATE
                            + " roots in scope `tool-groovy`: " + selections(groovyRoots));
        }
        if (directRoots.size() > 1) {
            List<ResolvedClasspathPackage> extras = directRoots.stream()
                    .filter(dependency -> dependency != groovyRoots.getFirst())
                    .toList();
            throw explicitInvalid(
                    sourceSet,
                    "zolt.lock has extra direct roots in scope `tool-groovy`: " + selections(extras));
        }

        ResolvedClasspathPackage rootDependency = groovyRoots.getFirst();
        ResolvedPackage rootPackage = rootDependency.resolvedPackage();
        if (!configuredVersion.equals(rootPackage.selectedVersion())) {
            throw explicitInvalid(
                    sourceSet,
                    "configured version `" + configuredVersion + "` does not match zolt.lock tool root version `"
                            + rootPackage.selectedVersion() + "`");
        }
        VerifiedLauncherArtifact root = verifiedLauncherArtifact(rootDependency, sourceSet, true);
        inspectJar(new Candidate(rootPackage.selectedVersion(), root.jar()), sourceSet);

        Map<String, VerifiedLauncherArtifact> closureByIdentity = new LinkedHashMap<>();
        addClosureArtifact(closureByIdentity, root, sourceSet);
        for (ResolvedClasspathPackage dependency : toolClosure) {
            if (dependency == rootDependency) {
                continue;
            }
            addClosureArtifact(
                    closureByIdentity,
                    verifiedLauncherArtifact(dependency, sourceSet, false),
                    sourceSet);
        }
        List<VerifiedLauncherArtifact> transitives = closureByIdentity.values().stream()
                .filter(artifact -> !artifact.artifactIdentity().canonicalKey()
                        .equals(root.artifactIdentity().canonicalKey()))
                .sorted(Comparator.comparing(artifact -> artifact.artifactIdentity().canonicalKey()))
                .toList();
        List<VerifiedLauncherArtifact> orderedClosure = new ArrayList<>();
        orderedClosure.add(root);
        orderedClosure.addAll(transitives);

        requireRuntime(all, sourceSet, configuredVersion, root.sha256());
        revalidateClosure(orderedClosure, sourceSet);

        List<Path> launcherJars = orderedClosure.stream()
                .map(VerifiedLauncherArtifact::jar)
                .toList();
        String closureIdentity = launcherClosureIdentity(orderedClosure);
        return new GroovyCompilerToolchain(
                configuredVersion,
                root.sha256(),
                launcherJars,
                closureIdentity);
    }

    private static VerifiedLauncherArtifact verifiedLauncherArtifact(
            ResolvedClasspathPackage dependency,
            SourceSet sourceSet,
            boolean requireDefaultVariant) {
        ResolvedPackage resolved = dependency.resolvedPackage();
        NestedArtifactIdentity identity = resolved.artifactIdentity();
        String coordinate = resolved.packageId() + ":" + resolved.selectedVersion();
        if (identity.sourceKind() != NestedArtifactIdentity.SourceKind.EXTERNAL) {
            throw explicitInvalid(
                    sourceSet,
                    "the `tool-groovy` closure contains a workspace substitution for " + coordinate);
        }
        if (!identity.packageId().equals(resolved.packageId())
                || !identity.version().equals(resolved.selectedVersion())) {
            throw explicitInvalid(
                    sourceSet,
                    "the resolved package and artifact identities disagree for " + coordinate);
        }
        if (!"jar".equals(identity.extension())) {
            throw explicitInvalid(
                    sourceSet,
                    "the `tool-groovy` closure contains a non-JAR artifact " + identity.coordinate());
        }
        if (requireDefaultVariant && identity.classifier().isPresent()) {
            throw explicitInvalid(
                    sourceSet,
                    GroovyCompilerToolchain.COORDINATE
                            + " is not the default unclassified JAR variant");
        }
        Path jar = resolved.jarPath().toAbsolutePath().normalize();
        return new VerifiedLauncherArtifact(
                identity,
                jar,
                verifiedExplicitHash(jar, sourceSet, identity.coordinate()));
    }

    private static void addClosureArtifact(
            Map<String, VerifiedLauncherArtifact> closure,
            VerifiedLauncherArtifact artifact,
            SourceSet sourceSet) {
        String key = artifact.artifactIdentity().canonicalKey();
        VerifiedLauncherArtifact existing = closure.putIfAbsent(key, artifact);
        if (existing != null
                && (!existing.jar().equals(artifact.jar())
                        || !existing.sha256().equals(artifact.sha256()))) {
            throw explicitInvalid(
                    sourceSet,
                    "the `tool-groovy` closure resolves " + artifact.artifactIdentity().coordinate()
                            + " ambiguously at " + existing.jar() + " and " + artifact.jar());
        }
    }

    private static void requireRuntime(
            List<ResolvedClasspathPackage> packages,
            SourceSet sourceSet,
            String configuredVersion,
            String compilerHash) {
        List<ResolvedClasspathPackage> visibleMatches = packages.stream()
                .filter(dependency -> dependency.scope() != DependencyScope.TOOL_GROOVY)
                .filter(dependency -> sourceSet.visible(dependency.scope()))
                .filter(dependency -> dependency.resolvedPackage().packageId().equals(GROOVY_PACKAGE))
                .toList();
        for (ResolvedClasspathPackage runtime : visibleMatches) {
            ResolvedPackage resolved = runtime.resolvedPackage();
            NestedArtifactIdentity identity = resolved.artifactIdentity();
            if (!identity.packageId().equals(resolved.packageId())
                    || !identity.version().equals(resolved.selectedVersion())) {
                throw explicitInvalid(
                        sourceSet,
                        "the resolved package and artifact identities disagree for ordinary runtime "
                                + resolved.packageId() + ":" + resolved.selectedVersion());
            }
        }
        List<ResolvedClasspathPackage> defaultRuntimes = visibleMatches.stream()
                .filter(dependency -> {
                    NestedArtifactIdentity identity = dependency.resolvedPackage().artifactIdentity();
                    return identity.sourceKind() == NestedArtifactIdentity.SourceKind.EXTERNAL
                            && "jar".equals(identity.extension())
                            && identity.classifier().isEmpty();
                })
                .toList();
        if (defaultRuntimes.isEmpty()) {
            throw explicitInvalid(
                    sourceSet,
                    "no ordinary source-set-visible external default JAR for "
                            + GroovyCompilerToolchain.COORDINATE
                            + " is present at configured version `" + configuredVersion + "`");
        }
        Map<RuntimeSelection, ResolvedClasspathPackage> distinct = new LinkedHashMap<>();
        for (ResolvedClasspathPackage runtime : defaultRuntimes) {
            ResolvedPackage resolved = runtime.resolvedPackage();
            distinct.putIfAbsent(
                    new RuntimeSelection(
                            resolved.selectedVersion(),
                            resolved.jarPath().toAbsolutePath().normalize()),
                    runtime);
        }
        if (distinct.size() != 1) {
            throw explicitInvalid(
                    sourceSet,
                    "ordinary source-set-visible external default " + GroovyCompilerToolchain.COORDINATE
                            + " runtime is ambiguous: " + selections(List.copyOf(distinct.values())));
        }
        ResolvedClasspathPackage runtime = distinct.values().iterator().next();
        String runtimeVersion = runtime.resolvedPackage().selectedVersion();
        if (!configuredVersion.equals(runtimeVersion)) {
            throw explicitInvalid(
                    sourceSet,
                    "ordinary runtime version `" + runtimeVersion + "` does not match configured version `"
                            + configuredVersion + "`");
        }
        VerifiedLauncherArtifact verified = verifiedLauncherArtifact(runtime, sourceSet, true);
        if (!compilerHash.equals(verified.sha256())) {
            throw explicitInvalid(
                    sourceSet,
                    "ordinary runtime " + GroovyCompilerToolchain.COORDINATE + ":" + runtimeVersion
                            + " does not have the same checksum-verified core content as the compiler root");
        }
    }

    private static void revalidateClosure(
            List<VerifiedLauncherArtifact> closure,
            SourceSet sourceSet) {
        for (VerifiedLauncherArtifact artifact : closure) {
            String current = verifiedExplicitHash(
                    artifact.jar(),
                    sourceSet,
                    artifact.artifactIdentity().coordinate());
            if (!artifact.sha256().equals(current)) {
                throw explicitInvalid(
                        sourceSet,
                        "the verified artifact identity changed while the `tool-groovy` closure was inspected");
            }
        }
    }

    private static String verifiedExplicitHash(
            Path jar,
            SourceSet sourceSet,
            String coordinate) {
        if (!Files.isRegularFile(jar)) {
            throw explicitInvalid(
                    sourceSet,
                    "the selected JAR for " + coordinate + " is not a regular file at " + jar);
        }
        return VerifiedArtifactHashes.currentHash(jar).orElseThrow(() -> explicitInvalid(
                sourceSet,
                "the selected JAR for " + coordinate + " at " + jar
                        + " has no current checksum-verified artifact identity"));
    }

    private static String launcherClosureIdentity(List<VerifiedLauncherArtifact> closure) {
        StringBuilder material = new StringBuilder();
        for (VerifiedLauncherArtifact artifact : closure) {
            material.append(artifact.artifactIdentity().canonicalKey())
                    .append("@sha256:")
                    .append(artifact.sha256())
                    .append('\n');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(
                    digest.digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new GroovyCompileException(
                    "Could not identify the verified Groovy compiler closure because SHA-256 is unavailable.",
                    exception);
        }
    }

    private static String selections(List<ResolvedClasspathPackage> dependencies) {
        return dependencies.stream()
                .map(dependency -> dependency.resolvedPackage().artifactIdentity().coordinate()
                        + " at " + dependency.resolvedPackage().jarPath().toAbsolutePath().normalize())
                .sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
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

    private static GroovyCompileException explicitInvalid(SourceSet sourceSet, String reason) {
        return new GroovyCompileException(
                "Configured Groovy " + sourceSet.label + " compiler toolchain is invalid because " + reason + ". "
                        + "Keep `[toolchain.groovy].version` and the ordinary Groovy runtime in "
                        + sourceSet.dependencySections + " aligned, run `zolt resolve`, and retry.");
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

    private record VerifiedLauncherArtifact(
            NestedArtifactIdentity artifactIdentity,
            Path jar,
            String sha256) {
    }

    private record RuntimeSelection(String version, Path jar) {
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
