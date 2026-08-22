package sh.zolt.explain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ExplainSignalsTest {
    @Test
    void definitionsHaveUniqueStableIds() {
        HashSet<String> ids = new HashSet<>();

        for (ExplainSignalDefinition definition : ExplainSignals.definitions()) {
            assertTrue(ids.add(definition.id()), "duplicate signal id " + definition.id());
            assertTrue(definition.id().startsWith("maven.") || definition.id().startsWith("gradle."));
        }
    }

    @Test
    void definitionsCoverInitialMavenAndGradleCategories() {
        assertDefinition(
                "maven.dependency.dynamic-version",
                ExplainSignal.Severity.BLOCK,
                ExplainSignal.Category.NON_DETERMINISM);
        assertDefinition(
                "maven.bom.detected",
                ExplainSignal.Severity.OK,
                ExplainSignal.Category.BUILDABILITY);
        assertDefinition(
                "maven.plugin.static-signal",
                ExplainSignal.Severity.WARN,
                ExplainSignal.Category.BUILDABILITY);
        assertDefinition(
                "maven.annotation-processor.path",
                ExplainSignal.Severity.WARN,
                ExplainSignal.Category.BUILDABILITY);
        assertDefinition(
                "gradle.build-src.detected",
                ExplainSignal.Severity.BLOCK,
                ExplainSignal.Category.MIGRATION_BLOCKER);
        assertDefinition(
                "gradle.project.build-file-name-unresolved",
                ExplainSignal.Severity.UNKNOWN,
                ExplainSignal.Category.BUILDABILITY);
        assertDefinition(
                "gradle.custom-task.detected",
                ExplainSignal.Severity.WARN,
                ExplainSignal.Category.BUILDABILITY);
        assertDefinition(
                "gradle.dependency.unresolved-notation",
                ExplainSignal.Severity.UNKNOWN,
                ExplainSignal.Category.BUILDABILITY);
    }

    @Test
    void frameworkNativeSignalsPointToTypedZoltSettings() {
        ExplainSignalDefinition maven = definition("maven.framework-native.unsupported");
        ExplainSignalDefinition gradle = definition("gradle.framework-native.unsupported");

        assertTrue(maven.nextStep().contains("[framework.spring-boot] native = true"));
        assertTrue(gradle.nextStep().contains("[framework.spring-boot] native = true"));
        assertTrue(maven.nextStep().contains("typed Zolt framework settings"));
        assertTrue(gradle.nextStep().contains("typed Zolt framework settings"));
        assertFalse(maven.nextStep().contains("need dedicated Zolt support"));
        assertFalse(gradle.nextStep().contains("need dedicated Zolt support"));
    }

    @Test
    void sortsSignalsByActionabilityThenCategoryProjectIdAndMessage() {
        List<ExplainSignal> sorted = ExplainSignals.sorted(List.of(
                signal(ExplainSignal.Severity.WARN, ExplainSignal.Category.BUILDABILITY, "b", "warn.b", "b"),
                signal(ExplainSignal.Severity.BLOCK, ExplainSignal.Category.MIGRATION_BLOCKER, "b", "block.b", "b"),
                signal(ExplainSignal.Severity.BLOCK, ExplainSignal.Category.BUILDABILITY, "z", "block.z", "z"),
                signal(ExplainSignal.Severity.UNKNOWN, ExplainSignal.Category.CACHEABILITY, "a", "unknown.a", "a"),
                signal(ExplainSignal.Severity.OK, ExplainSignal.Category.BUILDABILITY, "a", "ok.a", "a"),
                signal(ExplainSignal.Severity.BLOCK, ExplainSignal.Category.BUILDABILITY, "a", "block.a", "a")));

        assertEquals(List.of(
                        "block.a",
                        "block.z",
                        "block.b",
                        "unknown.a",
                        "warn.b",
                        "ok.a"),
                sorted.stream().map(ExplainSignal::id).toList());
    }

    private static void assertDefinition(
            String id,
            ExplainSignal.Severity severity,
            ExplainSignal.Category category) {
        ExplainSignalDefinition definition = definition(id);

        assertEquals(severity, definition.severity());
        assertEquals(category, definition.category());
    }

    private static ExplainSignalDefinition definition(String id) {
        return ExplainSignals.definitions().stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static ExplainSignal signal(
            ExplainSignal.Severity severity,
            ExplainSignal.Category category,
            String project,
            String id,
            String message) {
        return new ExplainSignal(severity, category, project, id, message, "next");
    }
}
