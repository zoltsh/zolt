package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.license.SpdxExpression;

final class LicensePolicyTermTest {
    @Test
    void canonicalizesSpdxButPreservesExactGenuinelyUnmappedGlobalTerms() {
        LicensePolicyTerm mit = LicensePolicyTerm.fromAuthored("mit");
        LicensePolicyTerm deprecated = LicensePolicyTerm.fromAuthored("GPL-2.0");
        LicensePolicyTerm prose = LicensePolicyTerm.fromAuthored("License With Restrictions");
        LicensePolicyTerm unsupported = LicensePolicyTerm.fromAuthored("LicenseRef-Internal");

        assertInstanceOf(LicensePolicyTerm.Spdx.class, mit);
        assertEquals("MIT", mit.value());
        assertEquals("GPL-2.0-only", deprecated.value());
        assertInstanceOf(LicensePolicyTerm.Raw.class, prose);
        assertEquals("License With Restrictions", prose.value());
        assertInstanceOf(LicensePolicyTerm.Raw.class, unsupported);
        assertEquals("LicenseRef-Internal", unsupported.value());
    }

    @Test
    void rejectsCompoundAndMalformedSpdxShapedGlobalValues() {
        for (String value : List.of(
                "",
                "MIT AND BSD-3-Clause",
                "MIT With Restrictions",
                "GPL-3.0-only MIT",
                "(MIT")) {
            assertThrows(IllegalArgumentException.class, () -> LicensePolicyTerm.fromAuthored(value), value);
        }
        assertThrows(IllegalArgumentException.class, () -> new LicensePolicyTerm.Raw("MIT"));
    }

    @Test
    void scopedTermsRequireExactCanonicalSpdxTermSpelling() {
        SpdxLicenseTerm with =
                new SpdxLicenseTerm("GPL-2.0-only WITH Classpath-exception-2.0");

        assertInstanceOf(SpdxExpression.With.class, with.expression());
        assertEquals("GPL-2.0-only WITH Classpath-exception-2.0", with.value());
        for (String value : List.of(
                "mit",
                "GPL-2.0",
                "License With Restrictions",
                "LicenseRef-Internal",
                "MIT OR BSD-3-Clause")) {
            assertThrows(IllegalArgumentException.class, () -> new SpdxLicenseTerm(value), value);
        }
    }
}
