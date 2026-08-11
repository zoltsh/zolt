package sh.zolt.sbom;

import java.util.Optional;
import sh.zolt.license.SpdxExpression;
import sh.zolt.license.SpdxExpressionParser;

/** Converts one raw declared name/URL into expression, SPDX-id, or unmapped evidence. */
final class DeclaredLicenseResolver {
    private final SpdxExpressionParser parser;
    private final SpdxLicenseMapping mapping;

    DeclaredLicenseResolver() {
        this(new SpdxExpressionParser(), new SpdxLicenseMapping());
    }

    DeclaredLicenseResolver(SpdxExpressionParser parser, SpdxLicenseMapping mapping) {
        this.parser = parser;
        this.mapping = mapping;
    }

    SbomLicense resolve(Optional<String> name, Optional<String> url) {
        Optional<SpdxExpression> explicit = name.flatMap(parser::tryParse);
        if (explicit.isPresent()) {
            return fromExpression(explicit.orElseThrow(), name, url);
        }
        Optional<String> mapped = mapping.spdxId(name, url);
        if (mapped.isEmpty()) {
            return SbomLicense.unmapped(name, url);
        }
        SpdxExpression normalized = parser.parse(mapped.orElseThrow());
        return fromExpression(normalized, name, url);
    }

    private static SbomLicense fromExpression(
            SpdxExpression expression,
            Optional<String> rawName,
            Optional<String> rawUrl) {
        if (expression instanceof SpdxExpression.License license) {
            return SbomLicense.spdx(license.id());
        }
        return SbomLicense.expression(expression, rawName, rawUrl);
    }
}
