package sh.zolt.license;

final class SpdxExpressionRenderer {
    private SpdxExpressionRenderer() {
    }

    static String render(SpdxExpression expression) {
        return render(expression, 0);
    }

    private static String render(SpdxExpression expression, int parentPrecedence) {
        int precedence = precedence(expression);
        String rendered = switch (expression) {
            case SpdxExpression.License license -> license.id();
            case SpdxExpression.With with -> with.licenseId() + " WITH " + with.exceptionId();
            case SpdxExpression.And and -> render(and.left(), precedence) + " AND " + render(and.right(), precedence);
            case SpdxExpression.Or or -> render(or.left(), precedence) + " OR " + render(or.right(), precedence);
        };
        return precedence < parentPrecedence ? "(" + rendered + ")" : rendered;
    }

    private static int precedence(SpdxExpression expression) {
        return switch (expression) {
            case SpdxExpression.License ignored -> 4;
            case SpdxExpression.With ignored -> 3;
            case SpdxExpression.And ignored -> 2;
            case SpdxExpression.Or ignored -> 1;
        };
    }
}
