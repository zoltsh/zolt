package sh.zolt.sbom;

import java.util.Optional;
import sh.zolt.license.SpdxExpression;

/**
 * One Maven license record for a component. Multiple records remain discrete alternatives because
 * Maven does not assign an operator to them; one record may itself carry an explicit SPDX expression.
 */
public record SbomLicense(
        SbomLicenseStatus status,
        Optional<String> spdxId,
        Optional<SpdxExpression> expression,
        Optional<String> name,
        Optional<String> url) {
    private static final SbomLicense UNKNOWN =
            new SbomLicense(
                    SbomLicenseStatus.UNKNOWN,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());

    public SbomLicense(
            SbomLicenseStatus status,
            Optional<String> spdxId,
            Optional<String> name,
            Optional<String> url) {
        this(status, spdxId, Optional.empty(), name, url);
    }

    public SbomLicense {
        spdxId = spdxId == null ? Optional.empty() : spdxId;
        expression = expression == null ? Optional.empty() : expression;
        name = name == null ? Optional.empty() : name;
        url = url == null ? Optional.empty() : url;
    }

    public static SbomLicense spdx(String id) {
        return new SbomLicense(
                SbomLicenseStatus.SPDX,
                Optional.of(id),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    public static SbomLicense expression(
            SpdxExpression expression,
            Optional<String> rawName,
            Optional<String> rawUrl) {
        return new SbomLicense(
                SbomLicenseStatus.SPDX_EXPRESSION,
                Optional.empty(),
                Optional.of(expression),
                rawName,
                rawUrl);
    }

    public static SbomLicense unmapped(Optional<String> name, Optional<String> url) {
        return new SbomLicense(
                SbomLicenseStatus.UNMAPPED,
                Optional.empty(),
                Optional.empty(),
                name,
                url);
    }

    public static SbomLicense unknown() {
        return UNKNOWN;
    }

    /** The label used to group this license in reports and to match it in the policy gate. */
    public String label() {
        return switch (status) {
            case SPDX -> spdxId.orElse("UNKNOWN");
            case SPDX_EXPRESSION -> expression.map(SpdxExpression::canonical).orElse("UNKNOWN");
            case UNMAPPED -> name.or(() -> url).orElse("(unspecified)");
            case UNKNOWN -> "UNKNOWN";
        };
    }

    /** The display name for a CycloneDX named-license object (UNMAPPED): the raw name, else the URL. */
    public String displayName() {
        return name.or(() -> expression.map(SpdxExpression::canonical)).or(() -> url).orElse("UNMAPPED");
    }
}
