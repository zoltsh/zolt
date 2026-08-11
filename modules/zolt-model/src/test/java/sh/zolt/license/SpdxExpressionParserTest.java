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
        assertEquals(695, catalog.licenseIds().size());
        assertEquals(32, catalog.deprecatedLicenseCount());
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
    void normalizesDeterministicDeprecatedIdentifiers() {
        assertEquals("GPL-2.0-only", parser.parse("GPL-2.0").canonical());
        assertEquals("LGPL-2.1-only", parser.parse("LGPL-2.1").canonical());
        assertEquals("LGPL-3.0-only", parser.parse("LGPL-3.0").canonical());
        assertEquals(
                "GPL-2.0-only WITH Classpath-exception-2.0",
                parser.parse("GPL-2.0-with-classpath-exception").canonical());
        assertEquals(
                "GPL-2.0-only WITH Font-exception-2.0",
                parser.parse("GPL-2.0-with-font-exception").canonical());
        assertEquals(
                "GPL-2.0-only WITH GCC-exception-2.0",
                parser.parse("GPL-2.0-with-GCC-exception").canonical());
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
        assertThrows(SpdxExpressionParseException.class, () -> parser.parse("Net-SNMP"));
    }

    @Test
    void rejectsMalformedOperatorsAndTrailingInput() {
        assertThrows(SpdxExpressionParseException.class, () -> parser.parse("MIT And BSD-3-Clause"));
        assertThrows(SpdxExpressionParseException.class, () -> parser.parse("MIT WITH MIT"));
        assertThrows(SpdxExpressionParseException.class, () -> parser.parse("MIT BSD-3-Clause"));
        assertThrows(SpdxExpressionParseException.class, () -> parser.parse("(MIT AND BSD-3-Clause"));
    }

    @Test
    void classifiesDeclaredLicenseSyntaxForSharedFailClosedHandling() {
        assertEquals(DeclaredLicenseSyntax.VALID_TERM, parser.classify("MIT"));
        assertEquals(
                DeclaredLicenseSyntax.VALID_EXPRESSION,
                parser.classify("MIT AND BSD-3-Clause"));
        assertEquals(DeclaredLicenseSyntax.UNSUPPORTED_ATOMIC, parser.classify("Net-SNMP"));
        assertEquals(DeclaredLicenseSyntax.UNSUPPORTED_ATOMIC, parser.classify("LicenseRef-Internal"));
        assertEquals(DeclaredLicenseSyntax.UNSUPPORTED_ATOMIC, parser.classify("GPL-2.0+"));
        assertEquals(DeclaredLicenseSyntax.MALFORMED_SPDX, parser.classify("GPL-3.0-only MIT"));
        assertEquals(
                DeclaredLicenseSyntax.MALFORMED_SPDX,
                parser.classify("MIT WITH Not-A-Real-Exception"));
        assertEquals(DeclaredLicenseSyntax.MALFORMED_SPDX, parser.classify("(MIT"));
        assertEquals(DeclaredLicenseSyntax.MALFORMED_SPDX, parser.classify("MIT)"));
        assertEquals(DeclaredLicenseSyntax.PROSE, parser.classify("The MIT License"));
        assertEquals(DeclaredLicenseSyntax.PROSE, parser.classify("License With Restrictions"));
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
        assertTrue(parser.isExpressionShaped("GPL-3.0-only MIT"));
        assertTrue(parser.isExpressionShaped("(MIT"));
        assertTrue(parser.isExpressionShaped("MIT)"));
        assertTrue(parser.isExpressionShaped("GPL-2.0+"));
        assertTrue(parser.isExpressionShaped("Net-SNMP"));
        assertTrue(parser.isExpressionShaped("LicenseRef-Internal"));
        assertTrue(parser.isExpressionShaped("AdditionRef-Custom"));
        assertTrue(parser.isExpressionShaped("DocumentRef-upstream:LicenseRef-Custom"));
        assertFalse(parser.isExpressionShaped("Business Friendly License"));
        assertFalse(parser.isExpressionShaped("Custom Internal License (2025)"));
        assertFalse(parser.isExpressionShaped("License With Restrictions"));
    }
}
