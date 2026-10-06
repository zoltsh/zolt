package sh.zolt.build.generatedsource.ksp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import java.util.jar.JarFile;
import sh.zolt.build.BuildException;
import sh.zolt.build.lockfile.VerifiedArtifactHashes;
import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.classpath.ResolvedPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;

/** Resolves checksum-verified, separately locked KSP engine and processor closures. */
final class KspJvmToolchainResolver {
    static final PackageId ENGINE =
            new PackageId("com.google.devtools.ksp", "symbol-processing-aa");
    static final String ENGINE_ENTRY =
            "com/google/devtools/ksp/cmdline/KSPJvmMain.class";
    static final String PROVIDER_ENTRY =
            "META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider";

    KspJvmToolchain resolve(
            List<ResolvedClasspathPackage> packages,
            String engineGroup,
            String processorGroup,
            String kotlinVersion,
            String kspVersion) {
        List<ResolvedClasspathPackage> all = packages == null ? List.of() : List.copyOf(packages);
        String engine = requireGroup(engineGroup, "KSP engine");
        String processors = requireGroup(processorGroup, "KSP processor");
        if (engine.equals(processors)) {
            throw invalid("engine and processor closures use the same tool group `" + engine + "`");
        }
        String kotlin = requireVersion(kotlinVersion, "Kotlin");
        String ksp = requireVersion(kspVersion, "KSP");
        requireCompatibleVersions(kotlin, ksp);

        List<ResolvedClasspathPackage> engineClosure = group(all, engine);
        List<ResolvedClasspathPackage> engineRoots = engineClosure.stream()
                .filter(dependency -> dependency.resolvedPackage().direct())
                .filter(dependency -> dependency.resolvedPackage().packageId().equals(ENGINE))
                .toList();
        if (engineRoots.size() != 1) {
            throw invalid("tool group `" + engine + "` must contain exactly one direct "
                    + ENGINE + " root, but found " + selections(engineRoots));
        }
        ResolvedClasspathPackage engineRoot = engineRoots.getFirst();
        if (!ksp.equals(engineRoot.resolvedPackage().selectedVersion())) {
            throw invalid("configured KSP version `" + ksp
                    + "` does not match engine root version `"
                    + engineRoot.resolvedPackage().selectedVersion() + "`");
        }
        List<ResolvedClasspathPackage> unexpectedEngineRoots = engineClosure.stream()
                .filter(dependency -> dependency.resolvedPackage().direct())
                .filter(dependency -> dependency != engineRoot)
                .toList();
        if (!unexpectedEngineRoots.isEmpty()) {
            throw invalid("tool group `" + engine + "` contains extra direct roots: "
                    + selections(unexpectedEngineRoots));
        }

        List<ResolvedClasspathPackage> processorClosure = group(all, processors);
        List<ResolvedClasspathPackage> processorRoots = processorClosure.stream()
                .filter(dependency -> dependency.resolvedPackage().direct())
                .toList();
        if (processorRoots.isEmpty()) {
            throw invalid("tool group `" + processors + "` has no direct KSP processor roots");
        }

        List<VerifiedArtifact> verifiedEngine = ordered(engineClosure, List.of(engineRoot));
        List<VerifiedArtifact> verifiedProcessors = ordered(processorClosure, processorRoots);
        requireEntry(verifiedEngine.getFirst().path(), ENGINE_ENTRY, "KSP engine");
        for (ResolvedClasspathPackage root : processorRoots) {
            requireEntry(artifact(root).path(), PROVIDER_ENTRY,
                    "KSP processor root " + root.resolvedPackage().packageId());
        }
        revalidate(verifiedEngine);
        revalidate(verifiedProcessors);

        return new KspJvmToolchain(
                ksp,
                kotlin,
                verifiedEngine.stream().map(VerifiedArtifact::path).toList(),
                verifiedProcessors.stream().map(VerifiedArtifact::path).toList(),
                "ksp:" + ksp + "|kotlin=" + kotlin + "|engine=" + identity(verifiedEngine)
                        + "|processors=" + identity(verifiedProcessors));
    }

    private static List<ResolvedClasspathPackage> group(
            List<ResolvedClasspathPackage> packages, String group) {
        List<ResolvedClasspathPackage> selected = packages.stream()
                .filter(dependency -> dependency.scope() == DependencyScope.TOOL_EXEC)
                .filter(dependency -> dependency.toolGroups().contains(group))
                .toList();
        if (selected.isEmpty()) {
            throw invalid("zolt.lock has no verified tool-exec closure for group `" + group + "`");
        }
        return selected;
    }

    private static List<VerifiedArtifact> ordered(
            List<ResolvedClasspathPackage> closure,
            List<ResolvedClasspathPackage> roots) {
        Map<String, VerifiedArtifact> artifacts = new LinkedHashMap<>();
        List<VerifiedArtifact> ordered = new ArrayList<>();
        roots.stream()
                .sorted(Comparator.comparing(KspJvmToolchainResolver::canonicalKey))
                .map(KspJvmToolchainResolver::artifact)
                .forEach(value -> add(artifacts, ordered, value));
        closure.stream()
                .sorted(Comparator.comparing(KspJvmToolchainResolver::canonicalKey))
                .map(KspJvmToolchainResolver::artifact)
                .forEach(value -> add(artifacts, ordered, value));
        return List.copyOf(ordered);
    }

    private static void add(
            Map<String, VerifiedArtifact> artifacts,
            List<VerifiedArtifact> ordered,
            VerifiedArtifact artifact) {
        VerifiedArtifact existing = artifacts.putIfAbsent(artifact.canonicalKey(), artifact);
        if (existing == null) {
            ordered.add(artifact);
            return;
        }
        if (!existing.path().equals(artifact.path()) || !existing.sha256().equals(artifact.sha256())) {
            throw invalid("tool closure resolves `" + artifact.coordinate()
                    + "` ambiguously at " + existing.path() + " and " + artifact.path());
        }
    }

    private static VerifiedArtifact artifact(ResolvedClasspathPackage dependency) {
        ResolvedPackage resolved = dependency.resolvedPackage();
        NestedArtifactIdentity identity = resolved.artifactIdentity();
        if (!identity.packageId().equals(resolved.packageId())
                || !identity.version().equals(resolved.selectedVersion())) {
            throw invalid("resolved package and artifact identities disagree for "
                    + resolved.packageId() + ":" + resolved.selectedVersion());
        }
        if (identity.sourceKind() != NestedArtifactIdentity.SourceKind.EXTERNAL) {
            throw invalid("tool closure contains a workspace substitution for " + identity.coordinate());
        }
        if (!"jar".equals(identity.extension()) || identity.classifier().isPresent()) {
            throw invalid("tool closure requires external default JARs, but found "
                    + identity.coordinate());
        }
        Path path = resolved.jarPath().toAbsolutePath().normalize();
        String sha256 = VerifiedArtifactHashes.currentHash(path).orElseThrow(() -> invalid(
                "could not hash verified tool artifact " + identity.coordinate()));
        return new VerifiedArtifact(identity.canonicalKey(), identity.coordinate(), path, sha256);
    }

    private static String canonicalKey(ResolvedClasspathPackage dependency) {
        return dependency.resolvedPackage().artifactIdentity().canonicalKey();
    }

    private static void requireEntry(Path jarPath, String entry, String label) {
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            if (jar.getJarEntry(entry) == null) {
                throw invalid("selected " + label + " JAR does not contain " + entry);
            }
        } catch (BuildException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new BuildException(
                    "Could not inspect the checksum-verified " + label + " JAR at " + jarPath
                            + ". Run `zolt resolve` to refresh the artifact cache, then retry.",
                    exception);
        }
    }

    private static void revalidate(List<VerifiedArtifact> artifacts) {
        for (VerifiedArtifact artifact : artifacts) {
            String current = VerifiedArtifactHashes.currentHash(artifact.path()).orElseThrow(() -> invalid(
                    "verified artifact changed while `" + artifact.coordinate() + "` was inspected"));
            if (!artifact.sha256().equals(current)) {
                throw invalid("verified artifact changed while `" + artifact.coordinate() + "` was inspected");
            }
        }
    }

    private static String identity(List<VerifiedArtifact> artifacts) {
        StringBuilder canonical = new StringBuilder();
        for (VerifiedArtifact artifact : artifacts) {
            canonical.append(artifact.canonicalKey())
                    .append('@')
                    .append(artifact.sha256())
                    .append('\n');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(
                    digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private static void requireCompatibleVersions(String kotlin, String ksp) {
        if (!ksp.startsWith(kotlin + "-") || ksp.length() == kotlin.length() + 1) {
            throw invalid("KSP version `" + ksp + "` is not built for configured Kotlin `" + kotlin
                    + "`; use the matching Kotlin-prefixed KSP release");
        }
    }

    private static String requireGroup(String value, String label) {
        if (value == null || value.isBlank()) {
            throw invalid(label + " tool group is required");
        }
        return value.strip();
    }

    private static String requireVersion(String value, String label) {
        if (value == null || value.isBlank()) {
            throw invalid(label + " version is required");
        }
        return value.strip();
    }

    private static String selections(List<ResolvedClasspathPackage> dependencies) {
        if (dependencies.isEmpty()) {
            return "none";
        }
        return dependencies.stream()
                .map(dependency -> dependency.resolvedPackage().artifactIdentity().coordinate())
                .sorted()
                .toList()
                .toString();
    }

    private static BuildException invalid(String reason) {
        return BuildException.actionable(
                "KSP toolchain is invalid: " + reason + ".",
                "Run `zolt resolve` to refresh the isolated KSP engine and processor closures, then retry.");
    }

    private record VerifiedArtifact(
            String canonicalKey,
            String coordinate,
            Path path,
            String sha256) {
        private VerifiedArtifact {
            Objects.requireNonNull(canonicalKey, "Canonical artifact key is required.");
            Objects.requireNonNull(coordinate, "Artifact coordinate is required.");
            Objects.requireNonNull(path, "Artifact path is required.");
            Objects.requireNonNull(sha256, "Artifact SHA-256 is required.");
        }
    }
}
