package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.LicensePolicyTerm;
import sh.zolt.project.UnknownLicensePolicy;

final class AuthoredLicensePolicyTest {
    @Test
    void sortsSemanticallyUnorderedTermsAndCopiesInputDeeply() {
        ArrayList<LicensePolicyTerm> allow = new ArrayList<>(List.of(
                LicensePolicyTerm.fromAuthored("Unicode-3.0"),
                LicensePolicyTerm.fromAuthored("Apache-2.0"),
                LicensePolicyTerm.fromAuthored("Business Friendly License")));
        AuthoredLicensePolicy policy = new AuthoredLicensePolicy(
                allow,
                List.of(LicensePolicyTerm.fromAuthored("GPL-3.0-only")),
                Optional.of(UnknownLicensePolicy.FAIL));
        allow.clear();

        assertEquals(
                List.of("Apache-2.0", "Business Friendly License", "Unicode-3.0"),
                policy.allow().stream().map(LicensePolicyTerm::value).toList());
        assertEquals(UnknownLicensePolicy.FAIL, policy.unknown().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> policy.allow().clear());
    }

    @Test
    void rejectsDuplicateCanonicalTermsAndAFieldlessTable() {
        assertThrows(IllegalArgumentException.class, () -> new AuthoredLicensePolicy(
                List.of(
                        LicensePolicyTerm.fromAuthored("MIT"),
                        LicensePolicyTerm.fromAuthored("mit")),
                List.of(),
                Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredLicensePolicy(
                List.of(), List.of(), Optional.empty()));
    }
}
