package sh.zolt.build.abi;

import java.util.LinkedHashSet;
import java.util.Set;

/** Extracts binary class references from field/method descriptors and JVM generic signatures. */
final class ClassFileSignatureReferences {
    private ClassFileSignatureReferences() {
    }

    static boolean add(String value, Set<String> referencedClasses) {
        Set<String> parsed = new LinkedHashSet<>();
        if (!new Parser(value, parsed).parse()) {
            return false;
        }
        referencedClasses.addAll(parsed);
        return true;
    }

    private static final class Parser {
        private final String value;
        private final Set<String> references;
        private int index;

        private Parser(String value, Set<String> references) {
            this.value = value == null ? "" : value;
            this.references = references;
        }

        private boolean parse() {
            if (value.isEmpty()) {
                return false;
            }
            if (peek('<') && !parseFormalTypeParameters()) {
                return false;
            }
            if (peek('(')) {
                return parseMethodSignature() && atEnd();
            }
            int typeCount = 0;
            while (!atEnd()) {
                if (!parseTypeSignature(true)) {
                    return false;
                }
                typeCount++;
            }
            return typeCount > 0;
        }

        private boolean parseMethodSignature() {
            index++;
            while (!atEnd() && !peek(')')) {
                if (!parseTypeSignature(false)) {
                    return false;
                }
            }
            if (!consume(')') || !parseTypeSignature(true)) {
                return false;
            }
            while (consume('^')) {
                if (atEnd() || (value.charAt(index) != 'L' && value.charAt(index) != 'T')) {
                    return false;
                }
                if (!parseFieldTypeSignature()) {
                    return false;
                }
            }
            return true;
        }

        private boolean parseFormalTypeParameters() {
            if (!consume('<')) {
                return false;
            }
            int count = 0;
            while (!atEnd() && !peek('>')) {
                if (!parseIdentifierUntil(':') || !consume(':')) {
                    return false;
                }
                if (!peek(':') && !parseFieldTypeSignature()) {
                    return false;
                }
                while (consume(':')) {
                    if (!parseFieldTypeSignature()) {
                        return false;
                    }
                }
                count++;
            }
            return count > 0 && consume('>');
        }

        private boolean parseTypeSignature(boolean allowVoid) {
            if (atEnd()) {
                return false;
            }
            char marker = value.charAt(index);
            if ("BCDFIJSZ".indexOf(marker) >= 0 || (allowVoid && marker == 'V')) {
                index++;
                return true;
            }
            return parseFieldTypeSignature();
        }

        private boolean parseFieldTypeSignature() {
            if (atEnd()) {
                return false;
            }
            return switch (value.charAt(index)) {
                case 'L' -> parseClassTypeSignature();
                case 'T' -> parseTypeVariableSignature();
                case '[' -> {
                    index++;
                    yield parseTypeSignature(false);
                }
                default -> false;
            };
        }

        private boolean parseClassTypeSignature() {
            if (!consume('L')) {
                return false;
            }
            String base = readClassName(true);
            if (base.isEmpty()) {
                return false;
            }
            StringBuilder binaryName = new StringBuilder(base);
            addReference(binaryName);
            if (peek('<') && !parseTypeArguments()) {
                return false;
            }
            while (consume('.')) {
                String nested = readClassName(false);
                if (nested.isEmpty()) {
                    return false;
                }
                binaryName.append('$').append(nested);
                addReference(binaryName);
                if (peek('<') && !parseTypeArguments()) {
                    return false;
                }
            }
            return consume(';');
        }

        private void addReference(StringBuilder internalName) {
            references.add(internalName.toString().replace('/', '.'));
        }

        private boolean parseTypeArguments() {
            if (!consume('<')) {
                return false;
            }
            int count = 0;
            while (!atEnd() && !peek('>')) {
                if (consume('*')) {
                    count++;
                    continue;
                }
                if (!consume('+')) {
                    consume('-');
                }
                if (!parseFieldTypeSignature()) {
                    return false;
                }
                count++;
            }
            return count > 0 && consume('>');
        }

        private boolean parseTypeVariableSignature() {
            if (!consume('T')) {
                return false;
            }
            return parseIdentifierUntil(';') && consume(';');
        }

        private String readClassName(boolean allowPackageSeparators) {
            int start = index;
            while (!atEnd()) {
                char current = value.charAt(index);
                if (current == '<' || current == '.' || current == ';') {
                    break;
                }
                if (!isNameCharacter(current) || (!allowPackageSeparators && current == '/')) {
                    return "";
                }
                index++;
            }
            String name = value.substring(start, index);
            if (name.isEmpty()
                    || name.startsWith("/")
                    || name.endsWith("/")
                    || name.contains("//")) {
                return "";
            }
            return name;
        }

        private boolean parseIdentifierUntil(char terminator) {
            int start = index;
            while (!atEnd() && value.charAt(index) != terminator) {
                if (!isNameCharacter(value.charAt(index)) || value.charAt(index) == '/') {
                    return false;
                }
                index++;
            }
            return index > start;
        }

        private static boolean isNameCharacter(char value) {
            return value != '<'
                    && value != '>'
                    && value != ':'
                    && value != ';'
                    && value != '.'
                    && value != '['
                    && value != ']'
                    && value != '('
                    && value != ')'
                    && value != '^'
                    && value != '+'
                    && value != '-'
                    && value != '*'
                    && !Character.isWhitespace(value);
        }

        private boolean consume(char expected) {
            if (!peek(expected)) {
                return false;
            }
            index++;
            return true;
        }

        private boolean peek(char expected) {
            return !atEnd() && value.charAt(index) == expected;
        }

        private boolean atEnd() {
            return index >= value.length();
        }
    }
}
