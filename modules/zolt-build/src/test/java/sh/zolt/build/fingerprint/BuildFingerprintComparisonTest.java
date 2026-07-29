package sh.zolt.build.fingerprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class BuildFingerprintComparisonTest {
    private final BuildFingerprintComparison comparison = new BuildFingerprintComparison();

    @Test
    void resourceOnlyChangeDoesNotInvalidateCompilation() {
        String existing = """
                version=1
                [sources]
                Main.java|source
                [resources]
                application.properties|old
                [expectedClasses]
                Main.class
                """;
        String current = existing.replace("application.properties|old", "application.properties|new");

        BuildFingerprintCheck check = comparison.compareForCompilation(existing, current);

        assertTrue(check.current());
        assertEquals("fingerprint-mismatch:resources", check.reason());
        assertFalse(comparison.compare(existing, current).current());
    }

    @Test
    void sourceChangeStillInvalidatesCompilationWhenResourcesAlsoChange() {
        String existing = """
                version=1
                [sources]
                Main.java|old
                [resources]
                application.properties|old
                """;
        String current = existing
                .replace("Main.java|old", "Main.java|new")
                .replace("application.properties|old", "application.properties|new");

        BuildFingerprintCheck check = comparison.compareForCompilation(existing, current);

        assertFalse(check.current());
        assertEquals("fingerprint-mismatch:sources", check.reason());
    }
}
