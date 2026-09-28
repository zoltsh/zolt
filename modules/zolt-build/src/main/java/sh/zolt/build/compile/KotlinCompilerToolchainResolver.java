package sh.zolt.build.compile;

import java.io.IOException;
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
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.lockfile.VerifiedArtifactHashes;
import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.classpath.ResolvedPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;

/** Resolves the explicitly configured, isolated Kotlin compiler closure for one source set. */
public final class KotlinCompilerToolchainResolver {
    private static final PackageId KOTLIN_COMPILER =
            new PackageId("org.jetbrains.kotlin", "kotlin-compiler-embeddable");
    private static final PackageId KOTLIN_STDLIB =
            new PackageId("org.jetbrains.kotlin", "kotlin-stdlib");
    private static final String COMPILER_ENTRY =
            "org/jetbrains/kotlin/cli/jvm/K2JVMCompiler.class";
    private static final String IMPLEMENTATION_TITLE = "kotlin-compiler-embeddable";

    public KotlinCompilerToolchain resolve(
            List<ResolvedClasspathPackage> packages,
            String configuredVersion) {
        return resolve(packages, configuredVersion, KotlinCompilationScope.MAIN);
    }

    public KotlinCompilerToolchain resolve(
            List<ResolvedClasspathPackage> packages,
            String configuredVersion,
            KotlinCompilationScope scope) {
        KotlinCompilationScope compilationScope = Objects.requireNonNull(
                scope,
                "Kotlin compilation scope is required.");
        String version = normalize(configuredVersion);
        if (version.isEmpty()) {
            throw invalid("`[toolchain.kotlin].version` is required");
        }
        List<ResolvedClasspathPackage> all = packages == null ? List.of() : List.copyOf(packages);
        List<ResolvedClasspathPackage> toolClosure = all.stream()
                .filter(dependency -> dependency.scope() == DependencyScope.TOOL_KOTLIN)
                .toList();
        List<ResolvedClasspathPackage> directRoots = toolClosure.stream()
                .filter(dependency -> dependency.resolvedPackage().direct())
                .toList();
        List<ResolvedClasspathPackage> compilerRoots = directRoots.stream()
                .filter(dependency -> dependency.resolvedPackage().packageId().equals(KOTLIN_COMPILER))
                .toList();

        requireOneCompilerRoot(compilerRoots, directRoots, version);
        ResolvedClasspathPackage rootDependency = compilerRoots.getFirst();
        ResolvedPackage rootPackage = rootDependency.resolvedPackage();
        if (!version.equals(rootPackage.selectedVersion())) {
            throw invalid("configured version `" + version
                    + "` does not match zolt.lock tool root version `"
                    + rootPackage.selectedVersion() + "`");
        }

        VerifiedCompilerArtifact root = verifiedClosureArtifact(rootDependency, true);
        List<VerifiedCompilerArtifact> closure = orderedClosure(toolClosure, rootDependency, root);
        inspectRoot(version, root.jar());
        VerifiedCompilerArtifact runtime = requireRuntime(all, version, compilationScope);
        revalidate(closure);
        revalidate(List.of(runtime));

        return new KotlinCompilerToolchain(
                version,
                root.sha256(),
                closure.stream().map(VerifiedCompilerArtifact::jar).toList(),
                closureIdentity(closure));
    }

    private static void requireOneCompilerRoot(
            List<ResolvedClasspathPackage> compilerRoots,
            List<ResolvedClasspathPackage> directRoots,
            String configuredVersion) {
        if (compilerRoots.isEmpty()) {
            throw invalid("zolt.lock has no direct " + KotlinCompilerToolchain.COORDINATE
                    + " root in scope `tool-kotlin` for configured version `"
                    + configuredVersion + "`");
        }
        if (compilerRoots.size() > 1) {
            throw invalid("zolt.lock has ambiguous direct " + KotlinCompilerToolchain.COORDINATE
                    + " roots in scope `tool-kotlin`: " + selections(compilerRoots));
        }
        if (directRoots.size() > 1) {
            List<ResolvedClasspathPackage> extras = directRoots.stream()
                    .filter(dependency -> dependency != compilerRoots.getFirst())
                    .toList();
            throw invalid("zolt.lock has extra direct roots in scope `tool-kotlin`: "
                    + selections(extras));
        }
    }

    private static List<VerifiedCompilerArtifact> orderedClosure(
            List<ResolvedClasspathPackage> toolClosure,
            ResolvedClasspathPackage rootDependency,
            VerifiedCompilerArtifact root) {
        Map<String, VerifiedCompilerArtifact> byIdentity = new LinkedHashMap<>();
        addClosureArtifact(byIdentity, root);
        for (ResolvedClasspathPackage dependency : toolClosure) {
            if (dependency != rootDependency) {
                addClosureArtifact(byIdentity, verifiedClosureArtifact(dependency, false));
            }
        }
        List<VerifiedCompilerArtifact> result = new ArrayList<>();
        result.add(root);
        byIdentity.values().stream()
                .filter(artifact -> !artifact.identity().canonicalKey()
                        .equals(root.identity().canonicalKey()))
                .sorted(Comparator.comparing(artifact -> artifact.identity().canonicalKey()))
                .forEach(result::add);
        return List.copyOf(result);
    }

    private static void addClosureArtifact(
            Map<String, VerifiedCompilerArtifact> closure,
            VerifiedCompilerArtifact artifact) {
        String key = artifact.identity().canonicalKey();
        VerifiedCompilerArtifact existing = closure.putIfAbsent(key, artifact);
        if (existing != null
                && (!existing.jar().equals(artifact.jar())
                        || !existing.sha256().equals(artifact.sha256()))) {
            throw invalid("the `tool-kotlin` closure resolves "
                    + artifact.identity().coordinate() + " ambiguously at "
                    + existing.jar() + " and " + artifact.jar());
        }
    }

    private static VerifiedCompilerArtifact verifiedClosureArtifact(
            ResolvedClasspathPackage dependency,
            boolean requireDefaultVariant) {
        ResolvedPackage resolved = dependency.resolvedPackage();
        NestedArtifactIdentity identity = resolved.artifactIdentity();
        String coordinate = resolved.packageId() + ":" + resolved.selectedVersion();
        requireMatchingIdentity(resolved, identity, coordinate);
        if (identity.sourceKind() != NestedArtifactIdentity.SourceKind.EXTERNAL) {
            throw invalid("the `tool-kotlin` closure contains a workspace substitution for "
                    + coordinate);
        }
        if (!"jar".equals(identity.extension())) {
            throw invalid("the `tool-kotlin` closure contains a non-JAR artifact "
                    + identity.coordinate());
        }
        if (requireDefaultVariant && identity.classifier().isPresent()) {
            throw invalid(KotlinCompilerToolchain.COORDINATE
                    + " is not the default unclassified JAR variant");
        }
        Path jar = resolved.jarPath().toAbsolutePath().normalize();
        return new VerifiedCompilerArtifact(
                identity,
                jar,
                verifiedHash(jar, identity.coordinate()));
    }

    private static VerifiedCompilerArtifact requireRuntime(
            List<ResolvedClasspathPackage> packages,
            String configuredVersion,
            KotlinCompilationScope scope) {
        List<ResolvedClasspathPackage> visibleMatches = packages.stream()
                .filter(dependency -> dependency.scope() != DependencyScope.TOOL_KOTLIN)
                .filter(dependency -> scope.includesRuntime(dependency.scope()))
                .filter(dependency -> dependency.resolvedPackage().packageId().equals(KOTLIN_STDLIB))
                .toList();
        for (ResolvedClasspathPackage runtime : visibleMatches) {
            ResolvedPackage resolved = runtime.resolvedPackage();
            requireMatchingIdentity(
                    resolved,
                    resolved.artifactIdentity(),
                    "ordinary runtime " + resolved.packageId() + ":" + resolved.selectedVersion());
        }
        List<ResolvedClasspathPackage> defaultRuntimes = visibleMatches.stream()
                .filter(KotlinCompilerToolchainResolver::isExternalDefaultJar)
                .toList();
        if (defaultRuntimes.isEmpty()) {
            throw invalid("no ordinary " + scope.label()
                    + "-source-set-visible external default JAR for "
                    + KOTLIN_STDLIB + " is present at configured version `"
                    + configuredVersion + "`");
        }
        Map<RuntimeSelection, ResolvedClasspathPackage> distinct = new LinkedHashMap<>();
        for (ResolvedClasspathPackage runtime : defaultRuntimes) {
            ResolvedPackage resolved = runtime.resolvedPackage();
            distinct.putIfAbsent(new RuntimeSelection(
                    resolved.selectedVersion(),
                    resolved.jarPath().toAbsolutePath().normalize()), runtime);
        }
        if (distinct.size() != 1) {
            throw invalid("ordinary " + scope.label()
                    + "-source-set-visible external default " + KOTLIN_STDLIB
                    + " runtime is ambiguous: " + selections(List.copyOf(distinct.values())));
        }
        ResolvedClasspathPackage runtime = distinct.values().iterator().next();
        ResolvedPackage resolved = runtime.resolvedPackage();
        if (!configuredVersion.equals(resolved.selectedVersion())) {
            throw invalid("ordinary runtime version `" + resolved.selectedVersion()
                    + "` does not match configured version `" + configuredVersion + "`");
        }
        NestedArtifactIdentity identity = resolved.artifactIdentity();
        Path jar = resolved.jarPath().toAbsolutePath().normalize();
        return new VerifiedCompilerArtifact(
                identity,
                jar,
                verifiedHash(jar, identity.coordinate()));
    }

    private static void requireMatchingIdentity(
            ResolvedPackage resolved,
            NestedArtifactIdentity identity,
            String coordinate) {
        if (!identity.packageId().equals(resolved.packageId())
                || !identity.version().equals(resolved.selectedVersion())) {
            throw invalid("the resolved package and artifact identities disagree for " + coordinate);
        }
    }

    private static boolean isExternalDefaultJar(ResolvedClasspathPackage dependency) {
        NestedArtifactIdentity identity = dependency.resolvedPackage().artifactIdentity();
        return identity.sourceKind() == NestedArtifactIdentity.SourceKind.EXTERNAL
                && "jar".equals(identity.extension())
                && identity.classifier().isEmpty();
    }

    private static void inspectRoot(String configuredVersion, Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            if (jar.getJarEntry(COMPILER_ENTRY) == null) {
                throw invalid("the selected compiler JAR does not contain " + COMPILER_ENTRY);
            }
            if (jar.getManifest() == null) {
                throw invalid("the selected compiler JAR has no manifest");
            }
            Attributes attributes = jar.getManifest().getMainAttributes();
            String title = normalize(attributes.getValue(Attributes.Name.IMPLEMENTATION_TITLE));
            if (!IMPLEMENTATION_TITLE.equals(title)) {
                throw invalid("the selected compiler JAR reports Implementation-Title `"
                        + title + "` instead of `" + IMPLEMENTATION_TITLE + "`");
            }
            String version = normalize(attributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION));
            if (!matchesConfiguredVersion(configuredVersion, version)) {
                throw invalid("the selected compiler JAR reports Implementation-Version `"
                        + version + "` which does not match configured version `"
                        + configuredVersion + "`");
            }
        } catch (KotlinCompileException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new KotlinCompileException(
                    "Could not inspect the checksum-verified Kotlin compiler JAR at " + jarPath
                            + ". Run `zolt resolve` to refresh the artifact cache, then retry.",
                    exception);
        }
    }

    private static boolean matchesConfiguredVersion(String configured, String reported) {
        if (configured.equals(reported)) {
            return true;
        }
        String releasePrefix = configured + "-release-";
        return reported.startsWith(releasePrefix) && reported.length() > releasePrefix.length();
    }

    private static void revalidate(List<VerifiedCompilerArtifact> artifacts) {
        for (VerifiedCompilerArtifact artifact : artifacts) {
            String current = VerifiedArtifactHashes.currentHash(artifact.jar()).orElseThrow(() -> invalid(
                    "the verified artifact identity changed while `" + artifact.identity().coordinate()
                            + "` was inspected"));
            if (!artifact.sha256().equals(current)) {
                throw invalid("the verified artifact identity changed while `"
                        + artifact.identity().coordinate() + "` was inspected");
            }
        }
    }

    private static String verifiedHash(Path jar, String coordinate) {
        if (!Files.isRegularFile(jar)) {
            throw invalid("the selected JAR for " + coordinate
                    + " is not a regular file at " + jar);
        }
        return VerifiedArtifactHashes.currentHash(jar).orElseThrow(() -> invalid(
                "the selected JAR for " + coordinate + " at " + jar
                        + " has no current checksum-verified artifact identity"));
    }

    private static String closureIdentity(List<VerifiedCompilerArtifact> closure) {
        StringBuilder material = new StringBuilder();
        for (VerifiedCompilerArtifact artifact : closure) {
            material.append(artifact.identity().canonicalKey())
                    .append("@sha256:").append(artifact.sha256()).append('\n');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(
                    digest.digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new KotlinCompileException(
                    "Could not identify the verified Kotlin compiler closure because SHA-256 is unavailable.",
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

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private static KotlinCompileException invalid(String reason) {
        return new KotlinCompileException(
                "Configured Kotlin compiler toolchain is invalid because " + reason + ". "
                        + "Keep `[toolchain.kotlin].version`, the `tool-kotlin` closure, and ordinary "
                        + KOTLIN_STDLIB + " aligned; declare the runtime in [dependencies] or "
                        + "[dependencies.test] as appropriate, run `zolt resolve`, and retry.");
    }

    private record VerifiedCompilerArtifact(
            NestedArtifactIdentity identity,
            Path jar,
            String sha256) {
    }

    private record RuntimeSelection(String version, Path jar) {
    }
}
