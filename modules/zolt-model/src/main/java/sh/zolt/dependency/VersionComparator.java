package sh.zolt.dependency;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class VersionComparator implements Comparator<String> {
    private static final Map<String, Integer> QUALIFIER_RANKS = Map.ofEntries(
            Map.entry("snapshot", -5),
            Map.entry("alpha", -4),
            Map.entry("a", -4),
            Map.entry("beta", -3),
            Map.entry("b", -3),
            Map.entry("milestone", -2),
            Map.entry("m", -2),
            Map.entry("rc", -1),
            Map.entry("cr", -1),
            Map.entry("", 0),
            Map.entry("final", 0),
            Map.entry("ga", 0),
            Map.entry("release", 0),
            Map.entry("sp", 1));

    @Override
    public int compare(String left, String right) {
        List<Part> leftParts = parts(left);
        List<Part> rightParts = parts(right);
        int max = Math.max(leftParts.size(), rightParts.size());
        for (int index = 0; index < max; index++) {
            Part leftPart = index < leftParts.size() ? leftParts.get(index) : Part.release();
            Part rightPart = index < rightParts.size() ? rightParts.get(index) : Part.release();
            int compared = leftPart.compareTo(rightPart);
            if (compared != 0) {
                return compared;
            }
        }
        return 0;
    }

    private static List<Part> parts(String version) {
        List<Part> parts = new ArrayList<>();
        for (String token : version.split("[.\\-_+]", -1)) {
            if (token.isBlank()) {
                continue;
            }
            splitToken(token, parts);
        }
        trimTrailingReleaseParts(parts);
        return parts;
    }

    private static void splitToken(String token, List<Part> parts) {
        StringBuilder current = new StringBuilder();
        Boolean numeric = null;
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            boolean characterNumeric = Character.isDigit(character);
            if (numeric != null && numeric != characterNumeric) {
                addPart(current, numeric, parts);
                current.setLength(0);
            }
            current.append(character);
            numeric = characterNumeric;
        }
        if (!current.isEmpty()) {
            addPart(current, Boolean.TRUE.equals(numeric), parts);
        }
    }

    private static void addPart(StringBuilder token, boolean numeric, List<Part> parts) {
        String value = token.toString();
        if (numeric) {
            parts.add(Part.numeric(new BigInteger(value)));
        } else {
            parts.add(Part.qualifier(value.toLowerCase(Locale.ROOT)));
        }
    }

    private static void trimTrailingReleaseParts(List<Part> parts) {
        while (!parts.isEmpty() && parts.getLast().releaseEquivalent()) {
            parts.removeLast();
        }
    }

    private record Part(BigInteger number, String qualifier) implements Comparable<Part> {
        static Part numeric(BigInteger value) {
            return new Part(value, null);
        }

        static Part qualifier(String value) {
            return new Part(null, value);
        }

        static Part release() {
            return new Part(BigInteger.ZERO, "");
        }

        @Override
        public int compareTo(Part other) {
            if (number != null && other.number != null) {
                return number.compareTo(other.number);
            }
            if (number != null) {
                return compareQualifierRanks("", other.qualifier);
            }
            if (other.number != null) {
                return compareQualifierRanks(qualifier, "");
            }
            return compareQualifierRanks(qualifier, other.qualifier);
        }

        boolean releaseEquivalent() {
            if (number != null) {
                return number.signum() == 0;
            }
            return QUALIFIER_RANKS.getOrDefault(qualifier, Integer.MIN_VALUE) == 0;
        }

        private static int compareQualifierRanks(String left, String right) {
            int leftRank = QUALIFIER_RANKS.getOrDefault(left, Integer.MIN_VALUE);
            int rightRank = QUALIFIER_RANKS.getOrDefault(right, Integer.MIN_VALUE);
            if (leftRank != rightRank) {
                return Integer.compare(leftRank, rightRank);
            }
            if (leftRank == Integer.MIN_VALUE) {
                return left.compareTo(right);
            }
            return 0;
        }
    }
}
