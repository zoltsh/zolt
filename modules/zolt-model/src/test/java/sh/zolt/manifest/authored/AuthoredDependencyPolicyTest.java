package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.DependencyConflictPolicy;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencyDenyEntry;
import sh.zolt.manifest.LicensePolicyTerm;
import sh.zolt.manifest.SpdxLicenseTerm;
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
    void defersSnapshotAvailabilityToEffectivePolicy() {
        AuthoredLicenseException exception = exception("BSD-3-Clause", Optional.of("0.8.4-SNAPSHOT"));
        AuthoredLicensePolicy licenses = licenses(
                List.of(LicensePolicyTerm.fromAuthored("MIT")),
                List.of(LicensePolicyTerm.fromAuthored("GPL-3.0-only")));

        AuthoredDependencyPolicy policy = new AuthoredDependencyPolicy(
                Optional.empty(),
                List.of(),
                Optional.of(licenses),
                Map.of(new DependencyCoordinate("org.example:matchit"), exception));

        assertEquals("0.8.4-SNAPSHOT", policy.licenseExceptions().values().iterator().next()
                .version().orElseThrow());
        assertEquals("GPL-3.0-only", policy.licenses().orElseThrow().deny().getFirst().value());
    }

    /**
     * Design §9.11 "Scoped license exceptions": global deny cannot be overridden. A manifest that
     * scopes an allowance of a globally denied license is a contradiction and is rejected while the
     * final model is built, not silently accepted and then denied at evaluation time.
     */
    @Test
    void rejectsAScopedAllowanceOfAGloballyDeniedLicense() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredDependencyPolicy(
                        Optional.empty(),
                        List.of(),
                        Optional.of(licenses(
                                List.of(LicensePolicyTerm.fromAuthored("MIT")),
                                List.of(LicensePolicyTerm.fromAuthored("BSD-3-Clause")))),
                        Map.of(
                                new DependencyCoordinate("org.example:matchit"),
                                exception("BSD-3-Clause", Optional.empty()))));

        assertTrue(
                failure.getMessage().contains("org.example:matchit"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("BSD-3-Clause"), failure.getMessage());
        assertTrue(failure.getMessage().contains("deny"), failure.getMessage());
    }

    /** Denying a base license denies every {@code LICENSE WITH EXCEPTION} form of it. */
    @Test
    void rejectsAScopedAllowanceOfAWithCombinationWhoseBaseLicenseIsDenied() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new AuthoredDependencyPolicy(
                        Optional.empty(),
                        List.of(),
                        Optional.of(licenses(
                                List.of(LicensePolicyTerm.fromAuthored("MIT")),
                                List.of(LicensePolicyTerm.fromAuthored("GPL-2.0-only")))),
                        Map.of(
                                new DependencyCoordinate("org.example:matchit"),
                                exception("GPL-2.0-only WITH Classpath-exception-2.0", Optional.empty()))));

        assertTrue(
                failure.getMessage().contains("GPL-2.0-only WITH Classpath-exception-2.0"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("GPL-2.0-only"), failure.getMessage());
    }

    @Test
    void acceptsAScopedAllowanceOfALicenseTheGlobalPolicyDoesNotDeny() {
        DependencyCoordinate coordinate = new DependencyCoordinate("org.example:matchit");

        AuthoredDependencyPolicy policy = new AuthoredDependencyPolicy(
                Optional.empty(),
                List.of(),
                Optional.of(licenses(
                        List.of(LicensePolicyTerm.fromAuthored("MIT")),
                        List.of(LicensePolicyTerm.fromAuthored("GPL-3.0-only")))),
                Map.of(coordinate, exception("BSD-3-Clause", Optional.empty())));

        assertEquals(
                "BSD-3-Clause",
                policy.licenseExceptions().get(coordinate).allow().getFirst().value());
    }

    /** A denied WITH combination does not deny the bare base license a scoped exception allows. */
    @Test
    void acceptsAScopedAllowanceOfABaseLicenseWhenOnlyAWithCombinationIsDenied() {
        DependencyCoordinate coordinate = new DependencyCoordinate("org.example:matchit");

        AuthoredDependencyPolicy policy = new AuthoredDependencyPolicy(
                Optional.empty(),
                List.of(),
                Optional.of(licenses(
                        List.of(LicensePolicyTerm.fromAuthored("MIT")),
                        List.of(LicensePolicyTerm.fromAuthored(
                                "GPL-2.0-only WITH Classpath-exception-2.0")))),
                Map.of(coordinate, exception("GPL-2.0-only", Optional.empty())));

        assertEquals(
                "GPL-2.0-only",
                policy.licenseExceptions().get(coordinate).allow().getFirst().value());
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
