package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.project.UnknownLicensePolicy;

final class AuthoredDependencyPolicyTest {
    @Test
    void retainsAllConflictModesByTheirFinalSymbols() {
        assertEquals(DependencyConflictPolicy.RESOLVE, DependencyConflictPolicy.fromId("resolve").orElseThrow());
        assertEquals(DependencyConflictPolicy.WARN, DependencyConflictPolicy.fromId("warn").orElseThrow());
        assertEquals(DependencyConflictPolicy.FAIL, DependencyConflictPolicy.fromId("fail").orElseThrow());
        assertEquals("resolve, warn, fail", DependencyConflictPolicy.supportedIds());
        assertEquals(Optional.empty(), DependencyConflictPolicy.fromId("strict"));
    }

    @Test
    void sortsAndCopiesDirectDependencyDenyEntries() {
        DependencyDenyEntry logging = deny("commons-logging:commons-logging", "Use SLF4J");
        DependencyDenyEntry vulnerable = deny("com.example:vulnerable", "Security policy");
        ArrayList<DependencyDenyEntry> source = new ArrayList<>(List.of(logging, vulnerable));

        AuthoredDependencyPolicy policy = new AuthoredDependencyPolicy(
                Optional.of(DependencyConflictPolicy.FAIL),
                source,
                Optional.empty(),
                Map.of());
        source.clear();

        assertEquals(List.of(vulnerable, logging), policy.deny());
        assertThrows(UnsupportedOperationException.class, () -> policy.deny().clear());
    }

    @Test
    void rejectsDuplicateDenyCoordinatesAndEmptyPolicy() {
        assertThrows(IllegalArgumentException.class, () -> new AuthoredDependencyPolicy(
                Optional.empty(),
                List.of(deny("bad:artifact", "one"), deny("bad:artifact", "two")),
                Optional.empty(),
                Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new AuthoredDependencyPolicy(
                Optional.empty(), List.of(), Optional.empty(), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DependencyDenyEntry(new DependencyCoordinate("bad:artifact"), Optional.of(" ")));
    }

    @Test
    void requiresARestrictiveGlobalAllowListForExceptionsAndSortsTheirCoordinates() {
        AuthoredLicensePolicy licenses = licenses(
                List.of(LicensePolicyTerm.fromAuthored("MIT")),
                List.of());
        DependencyCoordinate two = new DependencyCoordinate("org.example:two");
        DependencyCoordinate one = new DependencyCoordinate("org.example:one");
        LinkedHashMap<DependencyCoordinate, AuthoredLicenseException> source = new LinkedHashMap<>();
        source.put(two, exception("BSD-3-Clause", Optional.empty()));
        source.put(one, exception("Apache-2.0", Optional.of("1.0.0")));

        AuthoredDependencyPolicy policy = new AuthoredDependencyPolicy(
                Optional.empty(), List.of(), Optional.of(licenses), source);
        source.clear();

        assertEquals(List.of(one, two), List.copyOf(policy.licenseExceptions().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> policy.licenseExceptions().clear());
        assertThrows(IllegalArgumentException.class, () -> new AuthoredDependencyPolicy(
                Optional.empty(),
                List.of(),
                Optional.of(licenses(List.of(), List.of(LicensePolicyTerm.fromAuthored("GPL-3.0-only")))),
                Map.of(one, exception("Apache-2.0", Optional.empty()))));
    }

    @Test
    void defersSnapshotAvailabilityAndGlobalDenyPrecedenceToEffectivePolicy() {
        AuthoredLicenseException exception = exception("BSD-3-Clause", Optional.of("0.8.4-SNAPSHOT"));
        AuthoredLicensePolicy licenses = licenses(
                List.of(LicensePolicyTerm.fromAuthored("MIT")),
                List.of(LicensePolicyTerm.fromAuthored("BSD-3-Clause")));

        AuthoredDependencyPolicy policy = new AuthoredDependencyPolicy(
                Optional.empty(),
                List.of(),
                Optional.of(licenses),
                Map.of(new DependencyCoordinate("org.example:matchit"), exception));

        assertEquals("0.8.4-SNAPSHOT", policy.licenseExceptions().values().iterator().next()
                .version().orElseThrow());
        assertEquals("BSD-3-Clause", policy.licenses().orElseThrow().deny().getFirst().value());
    }

    @Test
    void validatesExceptionVersionReasonAndCanonicalTermsLocally() {
        for (String version : List.of("", "[1.0,2.0)", "1.+", "LATEST", "${version}", "1.0.")) {
            assertThrows(IllegalArgumentException.class, () -> exception("MIT", Optional.of(version)), version);
        }
        assertThrows(IllegalArgumentException.class,
                () -> new AuthoredLicenseException(List.of(new SpdxLicenseTerm("MIT")), Optional.empty(), " "));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthoredLicenseException(List.of(), Optional.empty(), "Reviewed"));
    }

    private static DependencyDenyEntry deny(String coordinate, String reason) {
        return new DependencyDenyEntry(new DependencyCoordinate(coordinate), Optional.of(reason));
    }

    private static AuthoredLicensePolicy licenses(
            List<LicensePolicyTerm> allow,
            List<LicensePolicyTerm> deny) {
        return new AuthoredLicensePolicy(allow, deny, Optional.of(UnknownLicensePolicy.FAIL));
    }

    private static AuthoredLicenseException exception(String term, Optional<String> version) {
        return new AuthoredLicenseException(
                List.of(new SpdxLicenseTerm(term)), version, "Reviewed dependency");
    }
}
