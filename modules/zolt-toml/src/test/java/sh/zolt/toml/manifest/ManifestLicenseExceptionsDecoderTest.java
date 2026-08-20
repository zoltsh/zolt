package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.authored.AuthoredDependencyPolicy;
import sh.zolt.manifest.authored.AuthoredLicenseException;
import sh.zolt.toml.ZoltConfigException;

final class ManifestLicenseExceptionsDecoderTest {
    @Test
    void explicitEmptyCollectionStaysSemanticallyEmptyAndImmutable() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [dependencies.license-exceptions]
                """);
        Map<DependencyCoordinate, AuthoredLicenseException> exceptions =
                new ManifestLicenseExceptionsDecoder().decode(index);

        assertTrue(exceptions.isEmpty());
        assertThrows(
                UnsupportedOperationException.class,
                () -> exceptions.put(
                        new DependencyCoordinate("a:b"),
                        null));
        assertTrue(new ManifestDependencyPolicyDecoder().decode(index).isEmpty());
    }

    @Test
    void decodesCanonicalTermsReasonsAndDeferredSnapshotVersionsIntoSortedPolicy() {
        AuthoredDependencyPolicy policy = decodePolicy("""
                [dependencies.policy.licenses]
                allow = ["MIT"]

                [dependencies.license-exceptions."org.example:zeta"]
                allow = ["Unicode-3.0", "BSD-3-Clause"]
                version = "0.8.4-SNAPSHOT"
                reason = "Reviewed zeta"

                [dependencies.license-exceptions."org.example:alpha"]
                allow = ["Apache-2.0"]
                reason = "Reviewed alpha"
                """).orElseThrow();

        assertEquals(
                List.of(
                        new DependencyCoordinate("org.example:alpha"),
                        new DependencyCoordinate("org.example:zeta")),
                List.copyOf(policy.licenseExceptions().keySet()));
        AuthoredLicenseException zeta = policy.licenseExceptions().get(
                new DependencyCoordinate("org.example:zeta"));
        assertEquals(
                List.of("BSD-3-Clause", "Unicode-3.0"),
                zeta.allow().stream().map(Object::toString).toList());
        assertEquals("0.8.4-SNAPSHOT", zeta.version().orElseThrow());
        assertEquals("Reviewed zeta", zeta.reason());
        assertThrows(
                UnsupportedOperationException.class,
                () -> policy.licenseExceptions().clear());
    }

    @Test
    void requiresBothMeaningfulAllowAndReasonAtConcreteNamedPaths() {
        assertFailure("""
                [dependencies.license-exceptions."org.example:demo"]
                reason = "Reviewed"
                """, "`dependencies.license-exceptions.org.example:demo.allow`");
        assertFailure("""
                [dependencies.license-exceptions."org.example:demo"]
                allow = ["MIT"]
                """, "`dependencies.license-exceptions.org.example:demo.reason`");
        assertFailure("""
                [dependencies.license-exceptions."org.example:demo"]
                allow = []
                reason = "Reviewed"
                """, "`dependencies.license-exceptions.org.example:demo.allow`", "must not be empty");
    }

    @Test
    void anchorsTermVersionReasonAndDuplicateFailuresToExactFieldsAndItems() {
        assertFailure(exception("[\"mit\"]", null, "Reviewed"),
                "`dependencies.license-exceptions.org.example:demo.allow[0]`",
                "not canonical");
        assertFailure(exception("[\"MIT\", \"MIT\"]", null, "Reviewed"),
                "`dependencies.license-exceptions.org.example:demo.allow[1]`",
                "declared more than once");
        assertFailure(exception("[\"MIT\"]", "LATEST", "Reviewed"),
                "`dependencies.license-exceptions.org.example:demo.version`",
                "Invalid license exception version");
        assertFailure(exception("[\"MIT\"]", null, " "),
                "`dependencies.license-exceptions.org.example:demo.reason`",
                "must not be blank");
    }

    @Test
    void anchorsMissingGlobalAllowToTheExceptionNamespaceAndDefersDenyWins() {
        assertFailure(exception("[\"MIT\"]", null, "Reviewed"),
                "[dependencies.license-exceptions]",
                "require a non-empty global license allow list");
        assertFailure("""
                [dependencies.policy.licenses]
                allow = []

                [dependencies.license-exceptions."org.example:demo"]
                allow = ["MIT"]
                reason = "Reviewed"
                """, "`dependencies.policy.licenses.allow`", "meaningful field");

        AuthoredDependencyPolicy policy = decodePolicy("""
                [dependencies.policy.licenses]
                allow = ["MIT"]
                deny = ["BSD-3-Clause"]

                [dependencies.license-exceptions."org.example:demo"]
                allow = ["BSD-3-Clause"]
                reason = "Reviewed"
                """).orElseThrow();
        assertEquals("BSD-3-Clause", policy.licenses().orElseThrow().deny().getFirst().value());
    }

    private static String exception(String allow, String version, String reason) {
        return "[dependencies.license-exceptions.\"org.example:demo\"]\n"
                + "allow = " + allow + "\n"
                + (version == null ? "" : "version = \"" + version + "\"\n")
                + "reason = \"" + reason + "\"\n";
    }

    private static Optional<AuthoredDependencyPolicy> decodePolicy(String source) {
        return new ManifestDependencyPolicyDecoder()
                .decode(ManifestSemanticTestSupport.index(source));
    }

    private static void assertFailure(String source, String... details) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodePolicy(source));
        for (String detail : details) {
            assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        }
    }
}
