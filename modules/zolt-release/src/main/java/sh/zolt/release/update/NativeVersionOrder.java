package sh.zolt.release.update;

import java.math.BigInteger;
import java.util.Comparator;

/**
 * SemVer precedence: numeric core, final above prereleases, then dot-separated prerelease
 * identifiers (numeric identifiers compare numerically and precede nonnumeric ones). Consequently
 * alpha < beta < rc < zap for one core; zap dates sort chronologically and commit IDs break same-day
 * ties lexically, not by commit age. Build metadata does not affect precedence; text breaks ties.
 * Legacy non-SemVer directory names sort below valid versions and then lexically.
 */
final class NativeVersionOrder implements Comparator<String> {
    static final NativeVersionOrder INSTANCE = new NativeVersionOrder();
    private static final java.util.regex.Pattern VERSION = java.util.regex.Pattern.compile(
            "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");

    @Override
    public int compare(String left, String right) {
        String[] a = parts(left);
        String[] b = parts(right);
        if (a == null || b == null) {
            return a == null && b == null ? left.compareTo(right) : a == null ? -1 : 1;
        }
        for (int i = 0; i < 3; i++) {
            int result = new BigInteger(a[i]).compareTo(new BigInteger(b[i]));
            if (result != 0) return result;
        }
        if (a[3].isEmpty() || b[3].isEmpty()) {
            if (!a[3].equals(b[3])) return a[3].isEmpty() ? 1 : -1;
        } else {
            String[] ap = a[3].split("\\.");
            String[] bp = b[3].split("\\.");
            for (int i = 0; i < Math.min(ap.length, bp.length); i++) {
                boolean an = ap[i].matches("[0-9]+");
                boolean bn = bp[i].matches("[0-9]+");
                int result = an && bn ? new BigInteger(ap[i]).compareTo(new BigInteger(bp[i]))
                        : an != bn ? (an ? -1 : 1) : ap[i].compareTo(bp[i]);
                if (result != 0) return result;
            }
            if (ap.length != bp.length) return Integer.compare(ap.length, bp.length);
        }
        return left.compareTo(right);
    }

    private static String[] parts(String version) {
        var match = VERSION.matcher(version);
        if (!match.matches()) return null;
        String prerelease = match.group(4) == null ? "" : match.group(4);
        for (String identifier : prerelease.split("\\.")) {
            if (identifier.length() > 1 && identifier.startsWith("0") && identifier.matches("[0-9]+")) return null;
        }
        return new String[] {match.group(1), match.group(2), match.group(3), prerelease};
    }
}
