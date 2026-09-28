package sh.zolt.build.compile;

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
import sh.zolt.build.GroovyCompileException;
import sh.zolt.build.lockfile.VerifiedArtifactHashes;
import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.classpath.ResolvedPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;

/** Resolves an explicitly configured, isolated Groovy compiler closure. */
final class ConfiguredGroovyCompilerToolchainResolver {
    private static final PackageId GROOVY_PACKAGE = new PackageId("org.apache.groovy", "groovy");

    GroovyCompilerToolchain resolve(
            List<ResolvedClasspathPackage> packages,
            GroovyCompilerToolchainResolver.SourceSet sourceSet,
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
            throw invalid(sourceSet, "zolt.lock has no direct " + GroovyCompilerToolchain.COORDINATE
                    + " root in scope `tool-groovy` for configured version `" + configuredVersion + "`");
        }
        if (groovyRoots.size() > 1) {
            throw invalid(sourceSet, "zolt.lock has ambiguous direct " + GroovyCompilerToolchain.COORDINATE
                    + " roots in scope `tool-groovy`: " + selections(groovyRoots));
        }
        if (directRoots.size() > 1) {
            List<ResolvedClasspathPackage> extras = directRoots.stream()
                    .filter(dependency -> dependency != groovyRoots.getFirst())
                    .toList();
            throw invalid(sourceSet, "zolt.lock has extra direct roots in scope `tool-groovy`: "
                    + selections(extras));
        }

        ResolvedClasspathPackage rootDependency = groovyRoots.getFirst();
        ResolvedPackage rootPackage = rootDependency.resolvedPackage();
        if (!configuredVersion.equals(rootPackage.selectedVersion())) {
            throw invalid(sourceSet, "configured version `" + configuredVersion
                    + "` does not match zolt.lock tool root version `" + rootPackage.selectedVersion() + "`");
        }
        VerifiedLauncherArtifact root = verifiedArtifact(rootDependency, sourceSet, true);
        GroovyCompilerToolchainResolver.inspectJar(rootPackage.selectedVersion(), root.jar(), sourceSet);

        List<VerifiedLauncherArtifact> orderedClosure = orderedClosure(toolClosure, rootDependency, root, sourceSet);
        requireRuntime(all, sourceSet, configuredVersion, root.sha256());
        revalidateClosure(orderedClosure, sourceSet);

        return new GroovyCompilerToolchain(
                configuredVersion,
                root.sha256(),
                orderedClosure.stream().map(VerifiedLauncherArtifact::jar).toList(),
                closureIdentity(orderedClosure));
    }

    private static List<VerifiedLauncherArtifact> orderedClosure(
            List<ResolvedClasspathPackage> toolClosure,
            ResolvedClasspathPackage rootDependency,
            VerifiedLauncherArtifact root,
            GroovyCompilerToolchainResolver.SourceSet sourceSet) {
        Map<String, VerifiedLauncherArtifact> closureByIdentity = new LinkedHashMap<>();
        addClosureArtifact(closureByIdentity, root, sourceSet);
        for (ResolvedClasspathPackage dependency : toolClosure) {
            if (dependency != rootDependency) {
                addClosureArtifact(closureByIdentity, verifiedArtifact(dependency, sourceSet, false), sourceSet);
            }
        }
        List<VerifiedLauncherArtifact> result = new ArrayList<>();
        result.add(root);
        closureByIdentity.values().stream()
                .filter(artifact -> !artifact.artifactIdentity().canonicalKey()
                        .equals(root.artifactIdentity().canonicalKey()))
                .sorted(Comparator.comparing(artifact -> artifact.artifactIdentity().canonicalKey()))
                .forEach(result::add);
        return List.copyOf(result);
    }

    private static VerifiedLauncherArtifact verifiedArtifact(
            ResolvedClasspathPackage dependency,
            GroovyCompilerToolchainResolver.SourceSet sourceSet,
            boolean requireDefaultVariant) {
        ResolvedPackage resolved = dependency.resolvedPackage();
        NestedArtifactIdentity identity = resolved.artifactIdentity();
        String coordinate = resolved.packageId() + ":" + resolved.selectedVersion();
        if (identity.sourceKind() != NestedArtifactIdentity.SourceKind.EXTERNAL) {
            throw invalid(sourceSet, "the `tool-groovy` closure contains a workspace substitution for "
                    + coordinate);
        }
        if (!identity.packageId().equals(resolved.packageId())
                || !identity.version().equals(resolved.selectedVersion())) {
            throw invalid(sourceSet, "the resolved package and artifact identities disagree for " + coordinate);
        }
        if (!"jar".equals(identity.extension())) {
            throw invalid(sourceSet, "the `tool-groovy` closure contains a non-JAR artifact "
                    + identity.coordinate());
        }
        if (requireDefaultVariant && identity.classifier().isPresent()) {
            throw invalid(sourceSet, GroovyCompilerToolchain.COORDINATE
                    + " is not the default unclassified JAR variant");
        }
        Path jar = resolved.jarPath().toAbsolutePath().normalize();
        return new VerifiedLauncherArtifact(identity, jar, verifiedHash(jar, sourceSet, identity.coordinate()));
    }

    private static void addClosureArtifact(
            Map<String, VerifiedLauncherArtifact> closure,
            VerifiedLauncherArtifact artifact,
            GroovyCompilerToolchainResolver.SourceSet sourceSet) {
        String key = artifact.artifactIdentity().canonicalKey();
        VerifiedLauncherArtifact existing = closure.putIfAbsent(key, artifact);
        if (existing != null
                && (!existing.jar().equals(artifact.jar()) || !existing.sha256().equals(artifact.sha256()))) {
            throw invalid(sourceSet, "the `tool-groovy` closure resolves "
                    + artifact.artifactIdentity().coordinate() + " ambiguously at " + existing.jar()
                    + " and " + artifact.jar());
        }
    }

    private static void requireRuntime(
            List<ResolvedClasspathPackage> packages,
            GroovyCompilerToolchainResolver.SourceSet sourceSet,
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
                throw invalid(sourceSet, "the resolved package and artifact identities disagree for ordinary runtime "
                        + resolved.packageId() + ":" + resolved.selectedVersion());
            }
        }
        List<ResolvedClasspathPackage> defaultRuntimes = visibleMatches.stream()
                .filter(ConfiguredGroovyCompilerToolchainResolver::isExternalDefaultJar)
                .toList();
        if (defaultRuntimes.isEmpty()) {
            throw invalid(sourceSet, "no ordinary source-set-visible external default JAR for "
                    + GroovyCompilerToolchain.COORDINATE + " is present at configured version `"
                    + configuredVersion + "`");
        }
        Map<RuntimeSelection, ResolvedClasspathPackage> distinct = new LinkedHashMap<>();
        for (ResolvedClasspathPackage runtime : defaultRuntimes) {
            ResolvedPackage resolved = runtime.resolvedPackage();
            distinct.putIfAbsent(new RuntimeSelection(
                    resolved.selectedVersion(), resolved.jarPath().toAbsolutePath().normalize()), runtime);
        }
        if (distinct.size() != 1) {
            throw invalid(sourceSet, "ordinary source-set-visible external default "
                    + GroovyCompilerToolchain.COORDINATE + " runtime is ambiguous: "
                    + selections(List.copyOf(distinct.values())));
        }
        ResolvedClasspathPackage runtime = distinct.values().iterator().next();
        String runtimeVersion = runtime.resolvedPackage().selectedVersion();
        if (!configuredVersion.equals(runtimeVersion)) {
            throw invalid(sourceSet, "ordinary runtime version `" + runtimeVersion
                    + "` does not match configured version `" + configuredVersion + "`");
        }
        VerifiedLauncherArtifact verified = verifiedArtifact(runtime, sourceSet, true);
        if (!compilerHash.equals(verified.sha256())) {
            throw invalid(sourceSet, "ordinary runtime " + GroovyCompilerToolchain.COORDINATE + ":"
                    + runtimeVersion
                    + " does not have the same checksum-verified core content as the compiler root");
        }
    }

    private static boolean isExternalDefaultJar(ResolvedClasspathPackage dependency) {
        NestedArtifactIdentity identity = dependency.resolvedPackage().artifactIdentity();
        return identity.sourceKind() == NestedArtifactIdentity.SourceKind.EXTERNAL
                && "jar".equals(identity.extension())
                && identity.classifier().isEmpty();
    }

    private static void revalidateClosure(
            List<VerifiedLauncherArtifact> closure,
            GroovyCompilerToolchainResolver.SourceSet sourceSet) {
        for (VerifiedLauncherArtifact artifact : closure) {
            String current = verifiedHash(artifact.jar(), sourceSet, artifact.artifactIdentity().coordinate());
            if (!artifact.sha256().equals(current)) {
                throw invalid(sourceSet,
                        "the verified artifact identity changed while the `tool-groovy` closure was inspected");
            }
        }
    }

    private static String verifiedHash(
            Path jar,
            GroovyCompilerToolchainResolver.SourceSet sourceSet,
            String coordinate) {
        if (!Files.isRegularFile(jar)) {
            throw invalid(sourceSet, "the selected JAR for " + coordinate + " is not a regular file at " + jar);
        }
        return VerifiedArtifactHashes.currentHash(jar).orElseThrow(() -> invalid(
                sourceSet,
                "the selected JAR for " + coordinate + " at " + jar
                        + " has no current checksum-verified artifact identity"));
    }

    private static String closureIdentity(List<VerifiedLauncherArtifact> closure) {
        StringBuilder material = new StringBuilder();
        for (VerifiedLauncherArtifact artifact : closure) {
            material.append(artifact.artifactIdentity().canonicalKey())
                    .append("@sha256:").append(artifact.sha256()).append('\n');
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

    private static GroovyCompileException invalid(
            GroovyCompilerToolchainResolver.SourceSet sourceSet,
            String reason) {
        return GroovyCompilerToolchainResolver.explicitInvalid(sourceSet, reason);
    }

    private record VerifiedLauncherArtifact(
            NestedArtifactIdentity artifactIdentity,
            Path jar,
            String sha256) {
    }

    private record RuntimeSelection(String version, Path jar) {
    }
}
