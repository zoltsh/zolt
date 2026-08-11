package sh.zolt.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class LicensePolicySettingsTest {
    @Test
    void acceptsConsistentExceptionIdentity() {
        LicensePolicyException exception = exception("org.example:lib");

        LicensePolicySettings settings = new LicensePolicySettings(
                List.of("MIT"),
                List.of(),
                UnknownLicensePolicy.FAIL,
                Map.of(exception.dependency(), exception));

        assertEquals(exception, settings.exceptions().get("org.example:lib"));
    }

    @Test
    void rejectsExceptionsWithoutGlobalAllowList() {
        LicensePolicyException exception = exception("org.example:lib");

        assertThrows(IllegalArgumentException.class, () -> new LicensePolicySettings(
                List.of(), List.of(), UnknownLicensePolicy.FAIL, Map.of(exception.dependency(), exception)));
    }

    @Test
    void rejectsNullExceptionValues() {
        Map<String, LicensePolicyException> exceptions = new LinkedHashMap<>();
        exceptions.put("org.example:lib", null);

        assertThrows(IllegalArgumentException.class, () -> new LicensePolicySettings(
                List.of("MIT"), List.of(), UnknownLicensePolicy.FAIL, exceptions));
    }

    @Test
    void rejectsMismatchedExceptionIdentity() {
        LicensePolicyException exception = exception("org.example:two");

        assertThrows(IllegalArgumentException.class, () -> new LicensePolicySettings(
                List.of("MIT"),
                List.of(),
                UnknownLicensePolicy.FAIL,
                Map.of("org.example:one", exception)));
    }

    @Test
    void rejectsDuplicateEmbeddedExceptionIdentity() {
        LicensePolicyException exception = exception("org.example:lib");
        Map<String, LicensePolicyException> exceptions = new LinkedHashMap<>();
        exceptions.put(exception.dependency(), exception);
        exceptions.put("org.example:other", exception);

        assertThrows(IllegalArgumentException.class, () -> new LicensePolicySettings(
                List.of("MIT"), List.of(), UnknownLicensePolicy.FAIL, exceptions));
    }

    private static LicensePolicyException exception(String dependency) {
        return new LicensePolicyException(
                dependency, List.of("BSD-3-Clause"), Optional.empty(), "Reviewed dependency");
    }
}
