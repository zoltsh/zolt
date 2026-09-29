package sh.zolt.explain.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import sh.zolt.dependency.DependencyLane;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

final class KotlinJvmDraftEligibilityTest {
    private static final String VERSION = "2.2.20";
    private static final String STDLIB = "org.jetbrains.kotlin:kotlin-stdlib";

    @Test
    void ignoresJavaOnlyProjectsEvenWhenPluginAndRuntimeEvidenceExist() {
        KotlinJvmDraftEligibility.Decision decision = decide(
                true,
                VERSION,
                List.of("src/main/java"),
                List.of("src/test/java"),
                dependencies -> dependencies.fixed(DependencyLane.IMPLEMENTATION, STDLIB, VERSION));

        assertInstanceOf(KotlinJvmDraftEligibility.Decision.NotApplicable.class, decision);
    }

    @Test
    void acceptsConventionalMainTestAndCombinedLayouts() {
        assertEligible(
                List.of("src/main/kotlin"),
                List.of(),
                DependencyLane.IMPLEMENTATION,
                true,
                false);
        assertEligible(
                List.of(),
                List.of("src/test/kotlin"),
                DependencyLane.TEST,
                false,
                true);
        assertEligible(
                List.of("src/main/java", "src/main/kotlin"),
                List.of("src/test/java", "src/test/kotlin"),
                DependencyLane.PROVIDED,
                true,
                true);
    }

    @Test
    void enforcesStdlibVisibilityForMainAndTestOnlyCompilation() {
        for (DependencyLane lane : List.of(
                DependencyLane.API,
                DependencyLane.IMPLEMENTATION,
                DependencyLane.PROVIDED)) {
            assertEligible(List.of("src/main/kotlin"), List.of(), lane, true, false);
        }
        for (DependencyLane lane : List.of(DependencyLane.RUNTIME, DependencyLane.TEST)) {
            assertReason(
                    decide(true, VERSION, List.of("src/main/kotlin"), List.of(),
                            dependencies -> dependencies.fixed(lane, STDLIB, VERSION)),
                    KotlinJvmDraftEligibility.Reason.STDLIB_SCOPE_NOT_VISIBLE);
            assertEligible(List.of(), List.of("src/test/kotlin"), lane, false, true);
        }
        assertReason(
                decide(true, VERSION, List.of(), List.of("src/test/kotlin"),
                        dependencies -> dependencies.fixed(DependencyLane.DEV, STDLIB, VERSION)),
                KotlinJvmDraftEligibility.Reason.STDLIB_SCOPE_NOT_VISIBLE);
        for (DependencyLane lane : List.of(
                DependencyLane.PROCESSOR,
                DependencyLane.TEST_PROCESSOR)) {
            assertReason(
                    decide(true, VERSION, List.of(), List.of("src/test/kotlin"),
                            dependencies -> dependencies.fixed(lane, STDLIB, VERSION)),
                    KotlinJvmDraftEligibility.Reason.STDLIB_MISSING);
        }
    }

    @Test
    void rejectsUnfixedPluginVersions() {
        for (String version : List.of("", " 2.2.20", "latest.release", "[2.2,3)", "2.2.20-SNAPSHOT", "${kotlin.version}")) {
            assertReason(
                    decide(true, version, List.of("src/main/kotlin"), List.of(),
                            dependencies -> dependencies.fixed(
                                    DependencyLane.IMPLEMENTATION, STDLIB, VERSION)),
                    KotlinJvmDraftEligibility.Reason.PLUGIN_VERSION_NOT_FIXED);
        }
    }

    @Test
    void rejectsMissingManagedWorkspaceAndAmbiguousStdlibEvidence() {
        assertReason(
                decide(true, VERSION, List.of("src/main/kotlin"), List.of(), dependencies -> {
                }),
                KotlinJvmDraftEligibility.Reason.STDLIB_MISSING);
        assertReason(
                decide(true, VERSION, List.of("src/main/kotlin"), List.of(),
                        dependencies -> dependencies.managed(
                                DependencyLane.IMPLEMENTATION,
                                STDLIB,
                                sh.zolt.manifest.authored.AuthoredDependencyMetadata.none())),
                KotlinJvmDraftEligibility.Reason.STDLIB_NOT_FIXED);
        assertReason(
                decide(true, VERSION, List.of("src/main/kotlin"), List.of(),
                        dependencies -> dependencies.workspaceMember(
                                DependencyLane.IMPLEMENTATION, STDLIB)),
                KotlinJvmDraftEligibility.Reason.STDLIB_NOT_FIXED);
        assertReason(
                decide(true, VERSION, List.of("src/main/kotlin"), List.of(), dependencies -> {
                    dependencies.fixed(DependencyLane.TEST, STDLIB, VERSION);
                    dependencies.fixed(DependencyLane.IMPLEMENTATION, STDLIB, VERSION);
                }),
                KotlinJvmDraftEligibility.Reason.STDLIB_AMBIGUOUS);
    }

    @Test
    void rejectsStdlibShapesTheSourceAdapterCannotProve() {
        DraftDependencies dependencies = new DraftDependencies(new ArrayList<>());
        dependencies.fixed(DependencyLane.IMPLEMENTATION, STDLIB, VERSION);

        assertReason(
                KotlinJvmDraftEligibility.decide(new KotlinJvmDraftEligibility.Input(
                        true,
                        VERSION,
                        false,
                        List.of("src/main/kotlin"),
                        List.of(),
                        dependencies)),
                KotlinJvmDraftEligibility.Reason.STDLIB_SHAPE_NOT_PROVEN);
    }

    @Test
    void rejectsStdlibVersionMismatch() {
        assertReason(
                decide(true, VERSION, List.of("src/main/kotlin"), List.of(),
                        dependencies -> dependencies.fixed(
                                DependencyLane.IMPLEMENTATION, STDLIB, "2.1.21")),
                KotlinJvmDraftEligibility.Reason.STDLIB_VERSION_MISMATCH);
    }

    @Test
    void rejectsUnprovenPluginAndNonConventionalOrMixedLanguageLayouts() {
        assertReason(
                decide(false, VERSION, List.of("src/main/kotlin"), List.of(),
                        dependencies -> dependencies.fixed(
                                DependencyLane.IMPLEMENTATION, STDLIB, VERSION)),
                KotlinJvmDraftEligibility.Reason.PLUGIN_SHAPE_NOT_PROVEN);
        for (List<String> mainRoots : List.of(
                List.of("src/kotlin"),
                List.of("src/main/kotlin", "src/generated/java"))) {
            assertReason(
                    decide(true, VERSION, mainRoots, List.of(),
                            dependencies -> dependencies.fixed(
                                    DependencyLane.IMPLEMENTATION, STDLIB, VERSION)),
                    KotlinJvmDraftEligibility.Reason.SOURCE_LAYOUT_NOT_CONVENTIONAL);
        }
        for (List<String> mainRoots : List.of(
                List.of("src/main/kotlin", "src/main/groovy"),
                List.of("src/main/kotlin", "src/main/scala"),
                List.of("src/main/kotlin", "src/main/android"))) {
            assertReason(
                    decide(true, VERSION, mainRoots, List.of(),
                            dependencies -> dependencies.fixed(
                                    DependencyLane.IMPLEMENTATION, STDLIB, VERSION)),
                    KotlinJvmDraftEligibility.Reason.INCOMPATIBLE_LANGUAGE);
        }
        assertReason(
                decide(true, VERSION, List.of(),
                        List.of("src/test/kotlin", "src/test/groovy"),
                        dependencies -> dependencies.fixed(DependencyLane.TEST, STDLIB, VERSION)),
                KotlinJvmDraftEligibility.Reason.INCOMPATIBLE_LANGUAGE);
    }

    private static void assertEligible(
            List<String> mainRoots,
            List<String> testRoots,
            DependencyLane lane,
            boolean main,
            boolean test) {
        KotlinJvmDraftEligibility.Decision.Eligible eligible = assertInstanceOf(
                KotlinJvmDraftEligibility.Decision.Eligible.class,
                decide(true, VERSION, mainRoots, testRoots,
                        dependencies -> dependencies.fixed(lane, STDLIB, VERSION)));
        assertEquals(VERSION, eligible.version().value());
        assertEquals(main, eligible.main());
        assertEquals(test, eligible.test());
    }

    private static void assertReason(
            KotlinJvmDraftEligibility.Decision decision,
            KotlinJvmDraftEligibility.Reason reason) {
        KotlinJvmDraftEligibility.Decision.NeedsReview review = assertInstanceOf(
                KotlinJvmDraftEligibility.Decision.NeedsReview.class, decision);
        assertEquals(reason, review.reason());
    }

    private static KotlinJvmDraftEligibility.Decision decide(
            boolean supportedPluginShape,
            String pluginVersion,
            List<String> mainRoots,
            List<String> testRoots,
            Consumer<DraftDependencies> configure) {
        DraftDependencies dependencies = new DraftDependencies(new ArrayList<>());
        configure.accept(dependencies);
        return KotlinJvmDraftEligibility.decide(new KotlinJvmDraftEligibility.Input(
                supportedPluginShape,
                pluginVersion,
                true,
                mainRoots,
                testRoots,
                dependencies));
    }
}
