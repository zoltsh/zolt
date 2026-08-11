package sh.zolt.license;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Finds complete SPDX identifiers inside declarations that are not valid expressions. */
final class SpdxSignalScanner {
    private static final Pattern REFERENCE = Pattern.compile(
            "(?:LicenseRef|AdditionRef)-[A-Za-z0-9.-]+"
                    + "|DocumentRef-[A-Za-z0-9.-]+:(?:LicenseRef|AdditionRef)-[A-Za-z0-9.-]+");
    private static final List<String> REFERENCE_PREFIXES =
            List.of("licenseref-", "additionref-", "documentref-");
    private final SpdxCatalog catalog;
    private final List<String> identifiers;

    SpdxSignalScanner(SpdxCatalog catalog) {
        this.catalog = catalog;
        List<String> known = new ArrayList<>();
        catalog.knownLicenseIds().forEach(value -> known.add(normalize(value)));
        catalog.exceptionIds().forEach(value -> known.add(normalize(value)));
        identifiers = List.copyOf(known);
    }

    boolean isUnsupportedAtomic(String source) {
        if (catalog.knownLicense(source).isPresent() || REFERENCE.matcher(source).matches()) {
            return true;
        }
        return source.endsWith("+")
                && catalog.knownLicense(source.substring(0, source.length() - 1)).isPresent();
    }

    boolean contains(String source) {
        String normalized = normalize(source);
        for (String identifier : identifiers) {
            if (containsAtBoundaries(normalized, identifier)) {
                return true;
            }
        }
        return containsReferencePrefix(normalized);
    }

    private static boolean containsAtBoundaries(String source, String identifier) {
        int offset = source.indexOf(identifier);
        while (offset >= 0) {
            int end = offset + identifier.length();
            if (isBoundary(source, offset - 1) && isBoundary(source, end)) {
                return true;
            }
            offset = source.indexOf(identifier, offset + 1);
        }
        return false;
    }

    private static boolean containsReferencePrefix(String source) {
        for (String prefix : REFERENCE_PREFIXES) {
            int offset = source.indexOf(prefix);
            while (offset >= 0) {
                if (isBoundary(source, offset - 1)) {
                    return true;
                }
                offset = source.indexOf(prefix, offset + 1);
            }
        }
        return false;
    }

    private static boolean isBoundary(String source, int offset) {
        return offset < 0
                || offset >= source.length()
                || !Character.isLetterOrDigit(source.charAt(offset));
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
