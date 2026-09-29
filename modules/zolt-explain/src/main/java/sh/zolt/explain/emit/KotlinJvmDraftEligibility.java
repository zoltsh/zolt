package sh.zolt.explain.emit;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.SourceRootLanguage;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.project.toolchain.KotlinToolchainVersion;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Shared fail-closed eligibility rules for Kotlin/JVM migration drafts. */
final class KotlinJvmDraftEligibility {
    private static final String STDLIB = "org.jetbrains.kotlin:kotlin-stdlib";
    private static final Set<String> MAIN_LAYOUT = Set.of("src/main/java", "src/main/kotlin");
    private static final Set<String> TEST_LAYOUT = Set.of("src/test/java", "src/test/kotlin");
    private static final Set<DependencyLane> MAIN_STDLIB_LANES = Set.of(
            DependencyLane.API,
            DependencyLane.IMPLEMENTATION,
            DependencyLane.PROVIDED);
    private static final Set<DependencyLane> TEST_STDLIB_LANES = Set.of(
            DependencyLane.API,
            DependencyLane.IMPLEMENTATION,
            DependencyLane.RUNTIME,
            DependencyLane.PROVIDED,
            DependencyLane.TEST);

    private KotlinJvmDraftEligibility() {
    }

    static Decision decide(Input input) {
        List<String> mainRoots = normalized(input.mainSourceRoots());
        List<String> testRoots = normalized(input.testSourceRoots());
        boolean mainKotlin = mainRoots.contains("src/main/kotlin");
        boolean testKotlin = testRoots.contains("src/test/kotlin");
        boolean anyKotlin = hasKotlinRoot(mainRoots) || hasKotlinRoot(testRoots);
        if (!anyKotlin) {
            return new Decision.NotApplicable();
        }
        if (hasIncompatibleLanguage(mainRoots) || hasIncompatibleLanguage(testRoots)) {
            return review(Reason.INCOMPATIBLE_LANGUAGE);
        }
        if (!MAIN_LAYOUT.containsAll(mainRoots)
                || !TEST_LAYOUT.containsAll(testRoots)
                || (!mainKotlin && !testKotlin)) {
            return review(Reason.SOURCE_LAYOUT_NOT_CONVENTIONAL);
        }
        if (!input.supportedPluginShape()) {
            return review(Reason.PLUGIN_SHAPE_NOT_PROVEN);
        }
        KotlinToolchainVersion compilerVersion = fixedCompilerVersion(input.pluginVersion());
        if (compilerVersion == null) {
            return review(Reason.PLUGIN_VERSION_NOT_FIXED);
        }
        if (!input.supportedStdlibShape()) {
            return review(Reason.STDLIB_SHAPE_NOT_PROVEN);
        }
        List<AuthoredDependency> candidates = input.dependencies().ordinaryCandidates(STDLIB);
        if (candidates.isEmpty()) {
            return review(Reason.STDLIB_MISSING);
        }
        if (candidates.size() != 1) {
            return review(Reason.STDLIB_AMBIGUOUS);
        }
        AuthoredDependency stdlib = candidates.getFirst();
        if (!(stdlib.selector() instanceof DependencySelector.FixedVersion fixed)) {
            return review(Reason.STDLIB_NOT_FIXED);
        }
        Set<DependencyLane> visibleLanes = mainKotlin ? MAIN_STDLIB_LANES : TEST_STDLIB_LANES;
        if (!visibleLanes.contains(stdlib.lane())) {
            return review(Reason.STDLIB_SCOPE_NOT_VISIBLE);
        }
        if (!compilerVersion.value().equals(fixed.value())) {
            return review(Reason.STDLIB_VERSION_MISMATCH);
        }
        return new Decision.Eligible(compilerVersion, mainKotlin, testKotlin);
    }

    private static Decision.NeedsReview review(Reason reason) {
        return new Decision.NeedsReview(reason);
    }

    private static KotlinToolchainVersion fixedCompilerVersion(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            return null;
        }
        try {
            return new KotlinToolchainVersion(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static List<String> normalized(List<String> roots) {
        return roots.stream()
                .map(String::strip)
                .map(root -> root.replace('\\', '/'))
                .toList();
    }

    private static boolean hasKotlinRoot(List<String> roots) {
        return roots.stream().anyMatch(root ->
                SourceRootLanguage.unsupported(root).orElse(null) == SourceRootLanguage.KOTLIN);
    }

    private static boolean hasIncompatibleLanguage(List<String> roots) {
        return roots.stream().anyMatch(root -> {
            SourceRootLanguage language = SourceRootLanguage.unsupported(root).orElse(null);
            return (language != null && language != SourceRootLanguage.KOTLIN)
                    || hasPathSegment(root, "groovy");
        });
    }

    private static boolean hasPathSegment(String root, String segment) {
        String value = root.toLowerCase(Locale.ROOT);
        return value.equals(segment)
                || value.startsWith(segment + "/")
                || value.endsWith("/" + segment)
                || value.contains("/" + segment + "/");
    }

    record Input(
            boolean supportedPluginShape,
            String pluginVersion,
            boolean supportedStdlibShape,
            List<String> mainSourceRoots,
            List<String> testSourceRoots,
            DraftDependencies dependencies) {
        Input {
            mainSourceRoots = List.copyOf(mainSourceRoots);
            testSourceRoots = List.copyOf(testSourceRoots);
        }
    }

    sealed interface Decision {
        record NotApplicable() implements Decision {
        }

        record Eligible(
                KotlinToolchainVersion version,
                boolean main,
                boolean test) implements Decision {
        }

        record NeedsReview(Reason reason) implements Decision {
        }
    }

    enum Reason {
        PLUGIN_SHAPE_NOT_PROVEN,
        PLUGIN_VERSION_NOT_FIXED,
        STDLIB_SHAPE_NOT_PROVEN,
        SOURCE_LAYOUT_NOT_CONVENTIONAL,
        INCOMPATIBLE_LANGUAGE,
        STDLIB_MISSING,
        STDLIB_NOT_FIXED,
        STDLIB_AMBIGUOUS,
        STDLIB_SCOPE_NOT_VISIBLE,
        STDLIB_VERSION_MISMATCH
    }
}
