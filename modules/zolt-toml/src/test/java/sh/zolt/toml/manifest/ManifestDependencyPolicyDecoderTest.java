package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.DependencyConflictPolicy;
import sh.zolt.manifest.DependencyDenyEntry;
import sh.zolt.manifest.authored.AuthoredDependencyPolicy;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.schema.FinalManifestPaths;

final class ManifestDependencyPolicyDecoderTest {
    @Test
    void omitsUnauthoredPolicyAndPreservesEmptyExceptionCollectionAsSyntaxOnly() {
        assertTrue(decode("").isEmpty());

        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [dependencies.license-exceptions]
                """);

        assertTrue(new ManifestDependencyPolicyDecoder().decode(index).isEmpty());
        assertTrue(index.section(FinalManifestPaths.DEPENDENCY_LICENSE_EXCEPTIONS)
                .orElseThrow()
                .source()
                .authoredTable());
    }

    @Test
    void decodesEveryExplicitConflictSymbolWithoutApplyingADefault() {
        for (DependencyConflictPolicy expected : DependencyConflictPolicy.values()) {
            AuthoredDependencyPolicy policy = decode("""
                    [dependencies.policy]
                    conflicts = "%s"
                    """.formatted(expected.id())).orElseThrow();

            assertEquals(expected, policy.conflicts().orElseThrow());
        }
    }

    @Test
    void decodesAndSortsImmutableDenyEntriesWhileRetainingReasons() {
        AuthoredDependencyPolicy policy = decode("""
                [dependencies.policy]
                deny = [
                    { coordinate = "org.example:zeta", reason = "blocked" },
                    { coordinate = "org.example:alpha" },
                ]
                """).orElseThrow();

        assertEquals(
                List.of("org.example:alpha", "org.example:zeta"),
                policy.deny().stream()
                        .map(entry -> entry.coordinate().value())
                        .toList());
        DependencyDenyEntry zeta = policy.deny().get(1);
        assertEquals("blocked", zeta.reason().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> policy.deny().clear());
    }

    @Test
    void rejectsSemanticallyEmptyDenyAtItsAuthoredFieldUnlessPolicyHasAJob() {
        assertFailure("""
                [dependencies.policy]
                deny = []
                """, "`dependencies.policy.deny`", "must not be empty");

        AuthoredDependencyPolicy meaningful = decode("""
                [dependencies.policy]
                conflicts = "resolve"
                deny = []
                """).orElseThrow();
        assertEquals(DependencyConflictPolicy.RESOLVE, meaningful.conflicts().orElseThrow());
        assertTrue(meaningful.deny().isEmpty());
    }

    @Test
    void anchorsDenyValueAndDuplicateFailuresToTheCausalItem() {
        assertFailure("""
                [dependencies.policy]
                deny = [{ coordinate = "invalid" }]
                """, "`dependencies.policy.deny[0].coordinate`", "Invalid dependency coordinate");
        assertFailure("""
                [dependencies.policy]
                deny = [{ coordinate = "a:b", reason = " " }]
                """, "`dependencies.policy.deny[0].reason`", "must not be blank");
        assertFailure("""
                [dependencies.policy]
                deny = [{ coordinate = "a:b" }, { coordinate = "a:b" }]
                """, "`dependencies.policy.deny[1]`", "declared more than once");
    }

    private static Optional<AuthoredDependencyPolicy> decode(String source) {
        return new ManifestDependencyPolicyDecoder()
                .decode(ManifestSemanticTestSupport.index(source));
    }

    private static void assertFailure(String source, String... details) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode(source));
        for (String detail : details) {
            assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        }
    }
}
