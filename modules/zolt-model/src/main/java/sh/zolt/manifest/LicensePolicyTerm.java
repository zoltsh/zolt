package sh.zolt.manifest;

import java.util.Objects;
import sh.zolt.license.DeclaredLicenseSyntax;
import sh.zolt.license.SpdxExpression;
import sh.zolt.license.SpdxExpressionParseException;
import sh.zolt.license.SpdxExpressionParser;

/** One global license-policy term: canonical SPDX or exact unmapped evidence text. */
public sealed interface LicensePolicyTerm extends Comparable<LicensePolicyTerm>
        permits LicensePolicyTerm.Spdx, LicensePolicyTerm.Raw {
    String value();

    /**
     * Classifies one authored global term. SPDX terms are canonicalized, while genuinely unmapped
     * labels retain their exact spelling. Compound or malformed SPDX-shaped values fail closed.
     */
    static LicensePolicyTerm fromAuthored(String value) {
        return LicensePolicyTermParser.fromAuthored(value);
    }

    @Override
    default int compareTo(LicensePolicyTerm other) {
        return ManifestModelValues.CODE_POINT_ORDER.compare(value(), other.value());
    }

    record Spdx(SpdxLicenseTerm term) implements LicensePolicyTerm {
        public Spdx {
            Objects.requireNonNull(term, "SPDX license policy term must not be null.");
        }

        @Override
        public String value() {
            return term.value();
        }

        @Override
        public String toString() {
            return value();
        }
    }

    record Raw(String value) implements LicensePolicyTerm {
        public Raw {
            LicensePolicyTermParser.requireRaw(value);
        }

        @Override
        public String toString() {
            return value;
        }
    }
}

final class LicensePolicyTermParser {
    private static final SpdxExpressionParser SPDX = new SpdxExpressionParser();

    private LicensePolicyTermParser() {}

    static LicensePolicyTerm fromAuthored(String value) {
        Objects.requireNonNull(value, "License policy term must not be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException("License policy term must not be blank.");
        }
        try {
            SpdxExpression parsed = SPDX.parseTerm(value);
            return new LicensePolicyTerm.Spdx(new SpdxLicenseTerm(parsed.canonical()));
        } catch (SpdxExpressionParseException exception) {
            DeclaredLicenseSyntax syntax = SPDX.classify(value);
            if (syntax == DeclaredLicenseSyntax.VALID_EXPRESSION
                    || syntax == DeclaredLicenseSyntax.MALFORMED_SPDX) {
                throw new IllegalArgumentException(
                        "Invalid SPDX-shaped global license policy term `" + value
                                + "`: use one SPDX term or an exact genuinely unmapped label.",
                        exception);
            }
            return new LicensePolicyTerm.Raw(value);
        }
    }

    static void requireRaw(String value) {
        Objects.requireNonNull(value, "Raw license policy term must not be null.");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Raw license policy term must not be blank.");
        }
        DeclaredLicenseSyntax syntax = SPDX.classify(value);
        if (syntax == DeclaredLicenseSyntax.VALID_TERM
                || syntax == DeclaredLicenseSyntax.VALID_EXPRESSION
                || syntax == DeclaredLicenseSyntax.MALFORMED_SPDX) {
            throw new IllegalArgumentException(
                    "Raw license policy term `" + value
                            + "` must be genuinely unmapped and must not contain valid or malformed SPDX syntax.");
        }
    }
}
