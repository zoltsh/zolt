package sh.zolt.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SpdxExpressionParserTest {
    private final SpdxExpressionParser parser = new SpdxExpressionParser();

    @Test
    void loadsPinnedLicenseAndExceptionCatalogs() {
        SpdxCatalog catalog = SpdxCatalog.defaultCatalog();

        assertEquals("3.28.0", SpdxCatalog.VERSION);
        assertEquals(727, catalog.licenseCount());
        assertEquals(84, catalog.exceptionCount());
        assertEquals("MIT", catalog.canonicalLicense("mit").orElseThrow());
        assertEquals(
                "Classpath-exception-2.0",
                catalog.canonicalException("classpath-exception-2.0").orElseThrow());
    }

    @Test
    void parsesAndCanonicalizesOperatorPrecedence() {
        SpdxExpression expression = parser.parse("mit or bsd-3-clause and apache-2.0");

        assertInstanceOf(SpdxExpression.Or.class, expression);
        assertEquals("MIT OR BSD-3-Clause AND Apache-2.0", expression.canonical());
    }

    @Test
    void preservesParenthesesThatChangePrecedence() {
        SpdxExpression expression = parser.parse("(MIT OR BSD-3-Clause) AND Apache-2.0");

        assertEquals("(MIT OR BSD-3-Clause) AND Apache-2.0", expression.canonical());
    }

    @Test
    void parsesWithAsOneTerm() {
        SpdxExpression expression = parser.parse("gpl-2.0-only with classpath-exception-2.0");

        assertEquals("GPL-2.0-only WITH Classpath-exception-2.0", expression.canonical());
        assertEquals(expression, parser.parseTerm(expression.canonical()));
    }

    @Test
    void normalizesCuratedDeprecatedClasspathAlias() {
        assertEquals(
                "GPL-2.0-only WITH Classpath-exception-2.0",
                parser.parse("GPL-2.0-with-classpath-exception").canonical());
    }

    @Test
    void normalizesCuratedDeprecatedAliasInsideACompoundExpression() {
        assertEquals(
                "GPL-2.0-only WITH Classpath-exception-2.0 OR MIT",
                parser.parse("GPL-2.0-with-classpath-exception OR MIT").canonical());
    }

    @Test
    void rejectsCompoundExpressionWhenOneTermIsRequired() {
        SpdxExpressionParseException exception = assertThrows(
                SpdxExpressionParseException.class,
                () -> parser.parseTerm("MIT AND BSD-3-Clause"));

        assertTrue(exception.getMessage().contains("one SPDX license term"), exception.getMessage());
    }

    @Test
    void rejectsUnknownAndUnsupportedIdentifiers() {
        assertThrows(SpdxExpressionParseException.class, () -> parser.parse("Not-A-Real-License"));
        assertThrows(SpdxExpressionParseException.class, () -> parser.parse("LicenseRef-Internal"));
        assertThrows(SpdxExpressionParseException.class, () -> parser.parse("GPL-2.0+"));
    }

    @Test
    void rejectsMalformedOperatorsAndTrailingInput() {
        assertThrows(SpdxExpressionParseException.class, () -> parser.parse("MIT And BSD-3-Clause"));
        assertThrows(SpdxExpressionParseException.class, () -> parser.parse("MIT WITH MIT"));
        assertThrows(SpdxExpressionParseException.class, () -> parser.parse("MIT BSD-3-Clause"));
        assertThrows(SpdxExpressionParseException.class, () -> parser.parse("(MIT AND BSD-3-Clause"));
    }

    @Test
    void rejectsExcessiveNesting() {
        String expression = "(".repeat(66) + "MIT" + ")".repeat(66);

        assertThrows(SpdxExpressionParseException.class, () -> parser.parse(expression));
    }

    @Test
    void detectsOnlyExpressionShapedRawValues() {
        assertTrue(parser.isExpressionShaped("MIT AND BSD-3-Clause"));
        assertTrue(parser.isExpressionShaped("MIT And BSD-3-Clause"));
        assertTrue(parser.isExpressionShaped("(MIT)"));
        assertTrue(parser.isExpressionShaped("MIT With Restrictions"));
        assertFalse(parser.isExpressionShaped("Business Friendly License"));
        assertFalse(parser.isExpressionShaped("Custom Internal License (2025)"));
        assertFalse(parser.isExpressionShaped("License With Restrictions"));
    }
}
