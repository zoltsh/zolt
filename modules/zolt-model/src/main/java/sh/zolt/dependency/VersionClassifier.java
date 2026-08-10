package sh.zolt.dependency;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * Classifies discovered version listings into per-class update targets, reusing {@link
 * VersionComparator} verbatim for all ordering and {@link VersionStability} for eligibility.
 *
 * <p>A candidate is considered only when it is strictly newer than the current version, is not a
 * SNAPSHOT, and — unless prereleases are requested — is a RELEASE. Change class is decided by the
 * leading numeric release core {@code major.minor.patch}: a differing major is MAJOR, a differing
 * minor is MINOR, otherwise PATCH. A prerelease current therefore sees a same-core GA as a PATCH.
 */
public final class VersionClassifier {
    private final VersionComparator comparator = new VersionComparator();

    /** Build the class-scoped update targets for a coordinate from a discovered version listing. */
    public VersionCandidates candidates(String current, List<String> listing, boolean includePrereleases) {
        ReleaseCore currentCore = ReleaseCore.of(current);
        String latestPatch = null;
        String latestMinor = null;
        String latestMajor = null;
        for (String candidate : listing) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            VersionStability stability = VersionStability.of(candidate);
            if (stability == VersionStability.SNAPSHOT) {
                continue;
            }
            if (stability == VersionStability.PRERELEASE && !includePrereleases) {
                continue;
            }
            if (comparator.compare(candidate, current) <= 0) {
                continue;
            }
            if (isNewer(candidate, latestMajor)) {
                latestMajor = candidate;
            }
            ReleaseCore candidateCore = ReleaseCore.of(candidate);
            if (candidateCore.major().equals(currentCore.major())) {
                if (isNewer(candidate, latestMinor)) {
                    latestMinor = candidate;
                }
                if (candidateCore.minor().equals(currentCore.minor()) && isNewer(candidate, latestPatch)) {
                    latestPatch = candidate;
                }
            }
        }
        return new VersionCandidates(
                current,
                Optional.ofNullable(latestPatch),
                Optional.ofNullable(latestMinor),
                Optional.ofNullable(latestMajor));
    }

    /** The change class of a single candidate relative to a current version. */
    public UpdateClass classify(String current, String candidate) {
        ReleaseCore currentCore = ReleaseCore.of(current);
        ReleaseCore candidateCore = ReleaseCore.of(candidate);
        if (!candidateCore.major().equals(currentCore.major())) {
            return UpdateClass.MAJOR;
        }
        if (!candidateCore.minor().equals(currentCore.minor())) {
            return UpdateClass.MINOR;
        }
        return UpdateClass.PATCH;
    }

    private boolean isNewer(String candidate, String incumbent) {
        return incumbent == null || comparator.compare(candidate, incumbent) > 0;
    }

    /** Leading numeric release core; missing segments default to zero. */
    record ReleaseCore(BigInteger major, BigInteger minor, BigInteger patch) {
        static ReleaseCore of(String version) {
            BigInteger[] core = {BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO};
            int index = 0;
            for (String segment : version.split("[.\\-_+]", -1)) {
                if (index >= core.length) {
                    break;
                }
                if (segment.isBlank()) {
                    continue;
                }
                BigInteger value = numericOrNull(segment);
                if (value == null) {
                    break;
                }
                core[index++] = value;
            }
            return new ReleaseCore(core[0], core[1], core[2]);
        }

        private static BigInteger numericOrNull(String segment) {
            for (int index = 0; index < segment.length(); index++) {
                if (!Character.isDigit(segment.charAt(index))) {
                    return null;
                }
            }
            return new BigInteger(segment);
        }
    }
}
