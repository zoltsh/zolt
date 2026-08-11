package sh.zolt.license;

/** A parsed SPDX license expression with canonical, precedence-preserving rendering. */
public sealed interface SpdxExpression {
    /** The canonical expression text using catalog identifier casing and uppercase operators. */
    default String canonical() {
        return SpdxExpressionRenderer.render(this);
    }

    /** One SPDX License List identifier. */
    record License(String id) implements SpdxExpression {
        public License {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("SPDX license id must not be blank.");
            }
        }
    }

    /** One license combined with one SPDX exception. */
    record With(String licenseId, String exceptionId) implements SpdxExpression {
        public With {
            if (licenseId == null || licenseId.isBlank() || exceptionId == null || exceptionId.isBlank()) {
                throw new IllegalArgumentException("SPDX WITH terms must not be blank.");
            }
        }
    }

    /** Conjunctive obligations: both branches apply. */
    record And(SpdxExpression left, SpdxExpression right) implements SpdxExpression {
        public And {
            if (left == null || right == null) {
                throw new IllegalArgumentException("SPDX AND branches must not be null.");
            }
        }
    }

    /** Disjunctive choice: either branch may be selected. */
    record Or(SpdxExpression left, SpdxExpression right) implements SpdxExpression {
        public Or {
            if (left == null || right == null) {
                throw new IllegalArgumentException("SPDX OR branches must not be null.");
            }
        }
    }
}
