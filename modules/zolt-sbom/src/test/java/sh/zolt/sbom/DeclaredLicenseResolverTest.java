package sh.zolt.sbom;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class DeclaredLicenseResolverTest {
    private final DeclaredLicenseResolver resolver = new DeclaredLicenseResolver();

    @Test
    void canonicalizesDeprecatedGnuIdentifiersBeforePolicyEvaluation() {
        assertLabels(
                List.of(
                        "GPL-2.0-only",
                        "LGPL-2.1-only",
                        "LGPL-3.0-only",
                        "GPL-2.0-only WITH Font-exception-2.0",
                        "GPL-2.0-only WITH GCC-exception-2.0"),
                List.of(
                        "GPL-2.0",
                        "LGPL-2.1",
                        "LGPL-3.0",
                        "GPL-2.0-with-font-exception",
                        "GPL-2.0-with-GCC-exception"));
    }

    @Test
    void invalidSpdxLookingNamesNeverFallThroughToRecognizableUrls() {
        assertUnmapped("MIT AND Not-A-License", "https://opensource.org/licenses/MIT");
        assertUnmapped("MIT And Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt");
        assertUnmapped("GPL-2.0+", "https://www.gnu.org/licenses/old-licenses/gpl-2.0.html");
        assertUnmapped("LicenseRef-Internal", "https://opensource.org/licenses/MIT");
        assertUnmapped("AdditionRef-Custom", "https://opensource.org/licenses/apache-2.0");
        assertUnmapped("DocumentRef-upstream:LicenseRef-Custom", "https://opensource.org/licenses/MIT");
        assertUnmapped("Net-SNMP", "https://opensource.org/licenses/MIT");
    }

    @Test
    void ordinaryProseNameCanStillUseConservativeUrlFallback() {
        SbomLicense license = resolver.resolve(
                Optional.of("Permissive license from project website"),
                Optional.of("https://opensource.org/licenses/MIT"));

        assertEquals(SbomLicenseStatus.SPDX, license.status());
        assertEquals("MIT", license.label());
    }

    private void assertUnmapped(String name, String url) {
        SbomLicense license = resolver.resolve(Optional.of(name), Optional.of(url));

        assertEquals(SbomLicenseStatus.UNMAPPED, license.status(), name);
        assertEquals(name, license.label());
        assertEquals(Optional.of(url), license.url());
    }

    private void assertLabels(List<String> expected, List<String> raw) {
        List<String> actual = raw.stream()
                .map(name -> resolver.resolve(Optional.of(name), Optional.empty()).label())
                .toList();
        assertEquals(expected, actual);
    }
}
