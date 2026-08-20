package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.LicensePolicyTerm;
import sh.zolt.manifest.authored.AuthoredLicensePolicy;
import sh.zolt.project.UnknownLicensePolicy;
import sh.zolt.toml.ZoltConfigException;

final class ManifestLicensePolicyDecoderTest {
    @Test
    void distinguishesOmissionAndDecodesSortedImmutableSpdxAndRawTerms() {
        assertTrue(decode("").isEmpty());

        AuthoredLicensePolicy policy = decode("""
                [dependencies.policy.licenses]
                allow = ["Unicode-3.0", "Business Friendly License", "Apache-2.0"]
                deny = ["GPL-3.0-only"]
                unknown = "fail"
                """).orElseThrow();

        assertEquals(
                List.of("Apache-2.0", "Business Friendly License", "Unicode-3.0"),
                policy.allow().stream().map(LicensePolicyTerm::value).toList());
        assertEquals("GPL-3.0-only", policy.deny().getFirst().value());
        assertEquals(UnknownLicensePolicy.FAIL, policy.unknown().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> policy.allow().clear());
    }

    @Test
    void decodesFieldsAuthoredThroughAnImplicitLicensePolicySection() {
        AuthoredLicensePolicy policy = decode("""
                [dependencies.policy]
                licenses.allow = ["MIT"]
                """).orElseThrow();

        assertEquals("MIT", policy.allow().getFirst().value());
    }

    @Test
    void retainsEveryUnknownPolicySymbolAndDefersDenyWinsEvaluation() {
        for (UnknownLicensePolicy expected : UnknownLicensePolicy.values()) {
            AuthoredLicensePolicy policy = decode("""
                    [dependencies.policy.licenses]
                    allow = ["MIT"]
                    deny = ["MIT"]
                    unknown = "%s"
                    """.formatted(expected.configValue())).orElseThrow();

            assertEquals(expected, policy.unknown().orElseThrow());
            assertEquals("MIT", policy.allow().getFirst().value());
            assertEquals("MIT", policy.deny().getFirst().value());
        }
    }

    @Test
    void rejectsFieldOnlyEmptyListsAtTheirExactLaterField() {
        assertFailure("""
                [dependencies.policy.licenses]
                allow = []
                """, "`dependencies.policy.licenses.allow`", "meaningful field");
        assertFailure("""
                [dependencies.policy.licenses]
                deny = []
                """, "`dependencies.policy.licenses.deny`", "meaningful field");
        assertFailure("""
                [dependencies.policy.licenses]
                allow = []
                deny = []
                """, "`dependencies.policy.licenses.deny`", "meaningful field");

        AuthoredLicensePolicy meaningful = decode("""
                [dependencies.policy.licenses]
                allow = []
                unknown = "warn"
                """).orElseThrow();
        assertTrue(meaningful.allow().isEmpty());
    }

    @Test
    void anchorsMalformedAndDuplicateTermsToTheirArrayItems() {
        assertFailure("""
                [dependencies.policy.licenses]
                allow = ["MIT AND Apache-2.0"]
                """, "`dependencies.policy.licenses.allow[0]`", "Invalid SPDX-shaped");
        assertFailure("""
                [dependencies.policy.licenses]
                allow = ["MIT", "mit"]
                """, "`dependencies.policy.licenses.allow[1]`", "declared more than once");
        assertFailure("""
                [dependencies.policy.licenses]
                deny = ["GPL-3.0-only", "gpl-3.0-only"]
                """, "`dependencies.policy.licenses.deny[1]`", "declared more than once");
    }

    private static Optional<AuthoredLicensePolicy> decode(String source) {
        return new ManifestLicensePolicyDecoder()
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
