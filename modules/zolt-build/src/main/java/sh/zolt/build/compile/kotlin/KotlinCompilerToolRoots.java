package sh.zolt.build.compile.kotlin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.dependency.PackageId;
import sh.zolt.project.toolchain.KotlinCompilerPlugin;

/** Validates the direct roots owned by the isolated Kotlin compiler tool closure. */
public final class KotlinCompilerToolRoots {
    public static final PackageId COMPILER =
            new PackageId("org.jetbrains.kotlin", "kotlin-compiler-embeddable");
    public static final PackageId KAPT =
            new PackageId("org.jetbrains.kotlin", "kotlin-annotation-processing-embeddable");
    public static final PackageId SERIALIZATION =
            new PackageId("org.jetbrains.kotlin", "kotlin-serialization-compiler-plugin-embeddable");
    public static final PackageId ALL_OPEN =
            new PackageId("org.jetbrains.kotlin", "kotlin-allopen-compiler-plugin-embeddable");
    public static final PackageId NO_ARG =
            new PackageId("org.jetbrains.kotlin", "kotlin-noarg-compiler-plugin-embeddable");
    public static final PackageId POWER_ASSERT = new PackageId(
            "org.jetbrains.kotlin", "kotlin-power-assert-compiler-plugin-embeddable");

    private KotlinCompilerToolRoots() {
    }

    public static Selection select(
            List<ResolvedClasspathPackage> directRoots,
            String configuredVersion,
            Set<KotlinCompilerPlugin> expectedPlugins) {
        List<ResolvedClasspathPackage> roots = List.copyOf(directRoots);
        Set<KotlinCompilerPlugin> plugins = Set.copyOf(Objects.requireNonNull(
                expectedPlugins,
                "Expected Kotlin compiler plugins are required."));
        List<ResolvedClasspathPackage> compilerRoots = matching(roots, COMPILER);
        List<ResolvedClasspathPackage> kaptRoots = matching(roots, KAPT);
        List<ResolvedClasspathPackage> serializationRoots = matching(roots, SERIALIZATION);
        List<ResolvedClasspathPackage> allOpenRoots = matching(roots, ALL_OPEN);
        List<ResolvedClasspathPackage> noArgRoots = matching(roots, NO_ARG);
        List<ResolvedClasspathPackage> powerAssertRoots = matching(roots, POWER_ASSERT);

        requireExactlyOneCompiler(compilerRoots, configuredVersion);
        requireAtMostOne(kaptRoots, KAPT, "KAPT");
        requireAtMostOne(serializationRoots, SERIALIZATION, "serialization compiler plugin");
        requireAtMostOne(allOpenRoots, ALL_OPEN, "all-open compiler plugin");
        requireAtMostOne(noArgRoots, NO_ARG, "JPA no-arg compiler plugin");
        requireAtMostOne(powerAssertRoots, POWER_ASSERT, "Power-assert compiler plugin");
        requireExpectedPluginRoots(
                plugins,
                serializationRoots,
                allOpenRoots,
                noArgRoots,
                powerAssertRoots,
                configuredVersion);
        rejectExtraRoots(roots, plugins);
        requireAlignedVersion(kaptRoots, configuredVersion, "KAPT tool root");
        requireAlignedVersion(
                serializationRoots,
                configuredVersion,
                "serialization compiler plugin tool root");
        requireAlignedVersion(
                allOpenRoots,
                configuredVersion,
                "all-open compiler plugin tool root");
        requireAlignedVersion(
                noArgRoots,
                configuredVersion,
                "JPA no-arg compiler plugin tool root");
        requireAlignedVersion(
                powerAssertRoots,
                configuredVersion,
                "Power-assert compiler plugin tool root");

        List<ResolvedClasspathPackage> pluginRoots = new ArrayList<>();
        if (plugins.contains(KotlinCompilerPlugin.SERIALIZATION)) {
            pluginRoots.add(serializationRoots.getFirst());
        }
        if (usesAllOpen(plugins)) {
            pluginRoots.add(allOpenRoots.getFirst());
        }
        if (plugins.contains(KotlinCompilerPlugin.JPA)) {
            pluginRoots.add(noArgRoots.getFirst());
        }
        if (plugins.contains(KotlinCompilerPlugin.POWER_ASSERT)) {
            pluginRoots.add(powerAssertRoots.getFirst());
        }
        return new Selection(
                compilerRoots.getFirst(),
                kaptRoots.isEmpty() ? Optional.empty() : Optional.of(kaptRoots.getFirst()),
                pluginRoots);
    }

    private static List<ResolvedClasspathPackage> matching(
            List<ResolvedClasspathPackage> roots,
            PackageId packageId) {
        return roots.stream()
                .filter(root -> root.resolvedPackage().packageId().equals(packageId))
                .toList();
    }

    private static void requireExactlyOneCompiler(
            List<ResolvedClasspathPackage> roots,
            String version) {
        if (roots.isEmpty()) {
            throw invalid("zolt.lock has no direct " + COMPILER
                    + " root in scope `tool-kotlin` for configured version `" + version + "`");
        }
        if (roots.size() > 1) {
            throw invalid("zolt.lock has ambiguous direct " + COMPILER
                    + " roots in scope `tool-kotlin`: " + selections(roots));
        }
    }

    private static void requireAtMostOne(
            List<ResolvedClasspathPackage> roots,
            PackageId packageId,
            String label) {
        if (roots.size() > 1) {
            throw invalid("zolt.lock has ambiguous direct " + packageId
                    + " " + label + " roots in scope `tool-kotlin`: " + selections(roots));
        }
    }

    private static void requireExpectedPluginRoots(
            Set<KotlinCompilerPlugin> plugins,
            List<ResolvedClasspathPackage> serializationRoots,
            List<ResolvedClasspathPackage> allOpenRoots,
            List<ResolvedClasspathPackage> noArgRoots,
            List<ResolvedClasspathPackage> powerAssertRoots,
            String version) {
        if (plugins.contains(KotlinCompilerPlugin.SERIALIZATION)
                && serializationRoots.isEmpty()) {
            throw invalid("zolt.lock has no direct " + SERIALIZATION
                    + " root in scope `tool-kotlin` for configured plugin `serialization` "
                    + "at version `" + version + "`");
        }
        if (usesAllOpen(plugins)
                && allOpenRoots.isEmpty()) {
            throw invalid("zolt.lock has no direct " + ALL_OPEN
                    + " root in scope `tool-kotlin` for a configured all-open selector "
                    + "at version `" + version + "`");
        }
        if (plugins.contains(KotlinCompilerPlugin.JPA)
                && noArgRoots.isEmpty()) {
            throw invalid("zolt.lock has no direct " + NO_ARG
                    + " root in scope `tool-kotlin` for configured plugin `jpa` "
                    + "at version `" + version + "`");
        }
        if (plugins.contains(KotlinCompilerPlugin.POWER_ASSERT)
                && powerAssertRoots.isEmpty()) {
            throw invalid("zolt.lock has no direct " + POWER_ASSERT
                    + " root in scope `tool-kotlin` for configured plugin `power-assert` "
                    + "at version `" + version + "`");
        }
    }

    private static void rejectExtraRoots(
            List<ResolvedClasspathPackage> roots,
            Set<KotlinCompilerPlugin> plugins) {
        List<ResolvedClasspathPackage> extras = roots.stream()
                .filter(root -> !root.resolvedPackage().packageId().equals(COMPILER))
                .filter(root -> !root.resolvedPackage().packageId().equals(KAPT))
                .filter(root -> !selectedPluginRoot(
                        root.resolvedPackage().packageId(), plugins))
                .toList();
        if (!extras.isEmpty()) {
            throw invalid("zolt.lock has extra direct roots in scope `tool-kotlin`: "
                    + selections(extras));
        }
    }

    private static boolean selectedPluginRoot(
            PackageId packageId,
            Set<KotlinCompilerPlugin> plugins) {
        return plugins.contains(KotlinCompilerPlugin.SERIALIZATION)
                        && packageId.equals(SERIALIZATION)
                || usesAllOpen(plugins)
                        && packageId.equals(ALL_OPEN)
                || plugins.contains(KotlinCompilerPlugin.JPA)
                        && packageId.equals(NO_ARG)
                || plugins.contains(KotlinCompilerPlugin.POWER_ASSERT)
                        && packageId.equals(POWER_ASSERT);
    }

    private static boolean usesAllOpen(Set<KotlinCompilerPlugin> plugins) {
        return plugins.contains(KotlinCompilerPlugin.SPRING)
                || plugins.contains(KotlinCompilerPlugin.MICRONAUT);
    }

    private static void requireAlignedVersion(
            List<ResolvedClasspathPackage> roots,
            String configuredVersion,
            String label) {
        if (roots.isEmpty()) {
            return;
        }
        String selected = roots.getFirst().resolvedPackage().selectedVersion();
        if (!configuredVersion.equals(selected)) {
            throw invalid("configured version `" + configuredVersion
                    + "` does not match zolt.lock " + label + " version `" + selected + "`");
        }
    }

    private static String selections(List<ResolvedClasspathPackage> roots) {
        return roots.stream()
                .map(root -> root.resolvedPackage().artifactIdentity().coordinate()
                        + " at " + root.resolvedPackage().jarPath().toAbsolutePath().normalize())
                .sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
    }

    private static KotlinCompileException invalid(String reason) {
        return new KotlinCompileException(
                "Configured Kotlin compiler toolchain is invalid because " + reason + ". "
                        + "Keep `[toolchain.kotlin].version`, the `tool-kotlin` closure, and ordinary "
                        + "org.jetbrains.kotlin:kotlin-stdlib aligned; declare the runtime in "
                        + "[dependencies] or [dependencies.test] as appropriate, run `zolt resolve`, "
                        + "and retry.");
    }

    public record Selection(
            ResolvedClasspathPackage compilerRoot,
            Optional<ResolvedClasspathPackage> kaptRoot,
            List<ResolvedClasspathPackage> compilerPluginRoots) {
        public Selection {
            Objects.requireNonNull(compilerRoot, "Kotlin compiler root is required.");
            kaptRoot = Objects.requireNonNull(kaptRoot, "KAPT root selection is required.");
            compilerPluginRoots = List.copyOf(compilerPluginRoots);
        }
    }
}
