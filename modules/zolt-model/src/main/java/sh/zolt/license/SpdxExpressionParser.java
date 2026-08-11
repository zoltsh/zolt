package sh.zolt.license;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Dependency-free parser for the SPDX identifier, WITH, AND, OR, and parentheses subset. */
public final class SpdxExpressionParser {
    private static final int MAX_LENGTH = 4096;
    private static final int MAX_DEPTH = 64;
    private static final Pattern EXPRESSION_OPERATOR =
            Pattern.compile("(?:^|[\\s(])(AND|OR|WITH)(?=$|[\\s)])", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> DEPRECATED_ALIASES = Map.of(
            "gpl-2.0-with-classpath-exception",
            "GPL-2.0-only WITH Classpath-exception-2.0");

    private final SpdxCatalog catalog;

    public SpdxExpressionParser() {
        this(SpdxCatalog.defaultCatalog());
    }

    SpdxExpressionParser(SpdxCatalog catalog) {
        this.catalog = catalog;
    }

    public SpdxExpression parse(String source) {
        if (source == null || source.isBlank()) {
            throw new SpdxExpressionParseException("SPDX expression must not be blank.");
        }
        String trimmed = source.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw new SpdxExpressionParseException("SPDX expression exceeds " + MAX_LENGTH + " characters.");
        }
        Parser parser = new Parser(trimmed);
        SpdxExpression expression = parser.expression(0);
        parser.require(TokenType.END, "end of expression");
        return expression;
    }

    public Optional<SpdxExpression> tryParse(String source) {
        try {
            return Optional.of(parse(source));
        } catch (SpdxExpressionParseException exception) {
            return Optional.empty();
        }
    }

    public SpdxExpression parseTerm(String source) {
        SpdxExpression expression = parse(source);
        if (expression instanceof SpdxExpression.And || expression instanceof SpdxExpression.Or) {
            throw new SpdxExpressionParseException(
                    "Expected one SPDX license term, not compound expression `" + expression.canonical() + "`.");
        }
        return expression;
    }

    public boolean isExpressionShaped(String source) {
        if (source == null
                || (source.indexOf('(') < 0
                        && source.indexOf(')') < 0
                        && !EXPRESSION_OPERATOR.matcher(source).find())) {
            return false;
        }
        Lexer lexer = new Lexer(source);
        Token token = lexer.next();
        while (token.type() != TokenType.END) {
            if (token.type() == TokenType.IDENTIFIER && isKnownLicense(token.text())) {
                return true;
            }
            token = lexer.next();
        }
        return false;
    }

    private boolean isKnownLicense(String identifier) {
        return catalog.canonicalLicense(identifier).isPresent()
                || DEPRECATED_ALIASES.containsKey(identifier.toLowerCase(Locale.ROOT));
    }

    private final class Parser {
        private final Lexer lexer;
        private Token current;

        private Parser(String source) {
            lexer = new Lexer(source);
            current = lexer.next();
        }

        private SpdxExpression expression(int depth) {
            requireDepth(depth);
            SpdxExpression left = and(depth + 1);
            while (current.type() == TokenType.OR) {
                advance();
                left = new SpdxExpression.Or(left, and(depth + 1));
            }
            return left;
        }

        private SpdxExpression and(int depth) {
            SpdxExpression left = with(depth);
            while (current.type() == TokenType.AND) {
                advance();
                left = new SpdxExpression.And(left, with(depth));
            }
            return left;
        }

        private SpdxExpression with(int depth) {
            SpdxExpression left = primary(depth);
            if (current.type() != TokenType.WITH) {
                return left;
            }
            if (!(left instanceof SpdxExpression.License license)) {
                throw error("WITH requires one license identifier on its left side");
            }
            advance();
            Token exception = require(TokenType.IDENTIFIER, "SPDX exception identifier");
            String canonical = catalog.canonicalException(exception.text())
                    .orElseThrow(() -> error("Unknown SPDX exception identifier `" + exception.text() + "`"));
            return new SpdxExpression.With(license.id(), canonical);
        }

        private SpdxExpression primary(int depth) {
            requireDepth(depth);
            if (current.type() == TokenType.LEFT_PAREN) {
                advance();
                SpdxExpression nested = expression(depth + 1);
                require(TokenType.RIGHT_PAREN, "`)`");
                return nested;
            }
            Token identifier = require(TokenType.IDENTIFIER, "SPDX license identifier or `(`");
            String alias = DEPRECATED_ALIASES.get(identifier.text().toLowerCase(Locale.ROOT));
            if (alias != null) {
                return SpdxExpressionParser.this.parseTerm(alias);
            }
            if (identifier.text().endsWith("+")) {
                throw new SpdxExpressionParseException(
                        "SPDX `+` suffixes are not supported in this release at character "
                                + identifier.offset()
                                + ".");
            }
            String canonical = catalog.canonicalLicense(identifier.text())
                    .orElseThrow(() -> error("Unknown SPDX license identifier `" + identifier.text() + "`"));
            return new SpdxExpression.License(canonical);
        }

        private Token require(TokenType expected, String description) {
            if (current.type() != expected) {
                throw error("Expected " + description + " but found `" + current.text() + "`");
            }
            Token result = current;
            advance();
            return result;
        }

        private void advance() {
            current = lexer.next();
        }

        private SpdxExpressionParseException error(String message) {
            return new SpdxExpressionParseException(message + " at character " + current.offset() + ".");
        }
    }

    private static final class Lexer {
        private final String source;
        private int offset;

        private Lexer(String source) {
            this.source = source;
        }

        private Token next() {
            while (offset < source.length() && Character.isWhitespace(source.charAt(offset))) {
                offset++;
            }
            if (offset == source.length()) {
                return new Token(TokenType.END, "<end>", offset);
            }
            int start = offset;
            char next = source.charAt(offset);
            if (next == '(') {
                offset++;
                return new Token(TokenType.LEFT_PAREN, "(", start);
            }
            if (next == ')') {
                offset++;
                return new Token(TokenType.RIGHT_PAREN, ")", start);
            }
            while (offset < source.length()) {
                char candidate = source.charAt(offset);
                if (Character.isWhitespace(candidate) || candidate == '(' || candidate == ')') {
                    break;
                }
                offset++;
            }
            String token = source.substring(start, offset);
            return new Token(operator(token), token, start);
        }

        private static TokenType operator(String token) {
            return switch (token) {
                case "AND", "and" -> TokenType.AND;
                case "OR", "or" -> TokenType.OR;
                case "WITH", "with" -> TokenType.WITH;
                default -> TokenType.IDENTIFIER;
            };
        }
    }

    private static void requireDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw new SpdxExpressionParseException("SPDX expression nesting exceeds " + MAX_DEPTH + " levels.");
        }
    }

    private enum TokenType {
        IDENTIFIER,
        AND,
        OR,
        WITH,
        LEFT_PAREN,
        RIGHT_PAREN,
        END
    }

    private record Token(TokenType type, String text, int offset) {
    }
}
