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
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.lockfile.VerifiedArtifactHashes;
import sh.zolt.build.compile.kotlin.KotlinCompilerToolRoots;
import sh.zolt.build.compile.kotlin.KotlinCompilerToolRoots.Selection;
import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.classpath.ResolvedPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.project.toolchain.KotlinCompilerPlugin;

/** Resolves the explicitly configured, isolated Kotlin compiler closure for one source set. */
public final class KotlinCompilerToolchainResolver {
    private static final PackageId KOTLIN_STDLIB =
            new PackageId("org.jetbrains.kotlin", "kotlin-stdlib");
    private static final String COMPILER_ENTRY =
            "org/jetbrains/kotlin/cli/jvm/K2JVMCompiler.class";
    private static final String IMPLEMENTATION_TITLE = "kotlin-compiler-embeddable";
    private static final String KAPT_ENTRY =
            "org/jetbrains/kotlin/kapt/KaptCommandLineProcessor.class";
    private static final String KAPT_IMPLEMENTATION_TITLE =
            "kotlin-annotation-processing-embeddable";
    private static final String SERIALIZATION_ENTRY =
            "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar";
    private static final String SERIALIZATION_IMPLEMENTATION_TITLE =
            "kotlinx-serialization-compiler-plugin.embeddable";

    public KotlinCompilerToolchain resolve(
            List<ResolvedClasspathPackage> packages,
            String configuredVersion) {
        return resolve(packages, configuredVersion, KotlinCompilationScope.MAIN, Set.of());
    }

    public KotlinCompilerToolchain resolve(
            List<ResolvedClasspathPackage> packages,
            String configuredVersion,
            KotlinCompilationScope scope) {
        return resolve(packages, configuredVersion, scope, Set.of());
    }

    public KotlinCompilerToolchain resolve(
            List<ResolvedClasspathPackage> packages,
            String configuredVersion,
            KotlinCompilationScope scope,
            Set<KotlinCompilerPlugin> expectedPlugins) {
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
        Selection roots = KotlinCompilerToolRoots.select(
                directRoots,
                version,
                expectedPlugins);
        ResolvedClasspathPackage rootDependency = roots.compilerRoot();
        ResolvedPackage rootPackage = rootDependency.resolvedPackage();
        if (!version.equals(rootPackage.selectedVersion())) {
            throw invalid("configured version `" + version
                    + "` does not match zolt.lock tool root version `"
                    + rootPackage.selectedVersion() + "`");
        }

        VerifiedCompilerArtifact root = verifiedClosureArtifact(rootDependency, true);
        VerifiedCompilerArtifact kapt = roots.kaptRoot()
                .map(dependency -> verifiedClosureArtifact(dependency, true))
                .orElse(null);
        List<VerifiedCompilerArtifact> plugins = roots.compilerPluginRoots().stream()
                .map(dependency -> verifiedClosureArtifact(dependency, true))
                .toList();
        List<VerifiedCompilerArtifact> closure = orderedClosure(toolClosure, rootDependency, root);
        inspectRoot(version, root.jar());
        if (kapt != null) {
            inspectKapt(version, kapt.jar());
        }
        if (!plugins.isEmpty()) {
            inspectSerialization(version, plugins.getFirst().jar());
        }
        VerifiedCompilerArtifact runtime = requireRuntime(all, version, compilationScope);
        revalidate(closure);
        revalidate(List.of(runtime));

        return new KotlinCompilerToolchain(
                version,
                root.sha256(),
                closure.stream().map(VerifiedCompilerArtifact::jar).toList(),
                closureIdentity(closure),
                kapt == null ? null : kapt.jar(),
                plugins.stream().map(VerifiedCompilerArtifact::jar).toList());
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
            throw invalid(resolved.packageId()
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
        inspectToolJar(
                configuredVersion,
                jarPath,
                COMPILER_ENTRY,
                IMPLEMENTATION_TITLE,
                "compiler");
    }

    private static void inspectKapt(String configuredVersion, Path jarPath) {
        inspectToolJar(
                configuredVersion,
                jarPath,
                KAPT_ENTRY,
                KAPT_IMPLEMENTATION_TITLE,
                "KAPT plugin");
    }

    private static void inspectSerialization(String configuredVersion, Path jarPath) {
        inspectToolJar(
                configuredVersion,
                jarPath,
                SERIALIZATION_ENTRY,
                SERIALIZATION_IMPLEMENTATION_TITLE,
                "serialization compiler plugin");
    }

    private static void inspectToolJar(
            String configuredVersion,
            Path jarPath,
            String requiredEntry,
            String implementationTitle,
            String label) {
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            if (jar.getJarEntry(requiredEntry) == null) {
                throw invalid("the selected " + label + " JAR does not contain " + requiredEntry);
            }
            if (jar.getManifest() == null) {
                throw invalid("the selected " + label + " JAR has no manifest");
            }
            Attributes attributes = jar.getManifest().getMainAttributes();
            String title = normalize(attributes.getValue(Attributes.Name.IMPLEMENTATION_TITLE));
            if (!implementationTitle.equals(title)) {
                throw invalid("the selected " + label + " JAR reports Implementation-Title `"
                        + title + "` instead of `" + implementationTitle + "`");
            }
            String version = normalize(attributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION));
            if (!matchesConfiguredVersion(configuredVersion, version)) {
                throw invalid("the selected " + label + " JAR reports Implementation-Version `"
                        + version + "` which does not match configured version `"
                        + configuredVersion + "`");
            }
        } catch (KotlinCompileException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new KotlinCompileException(
                    "Could not inspect the checksum-verified Kotlin " + label + " JAR at " + jarPath
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
