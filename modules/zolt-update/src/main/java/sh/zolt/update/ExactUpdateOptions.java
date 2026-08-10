package sh.zolt.update;

/** Caller-selected destination and the one stability opt-in allowed by exact update mode. */
public record ExactUpdateOptions(
        String toVersion,
        boolean includePrereleases) {
}
