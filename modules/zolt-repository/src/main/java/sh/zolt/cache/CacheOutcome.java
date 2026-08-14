package sh.zolt.cache;

/** What a scoped cache lookup actually did. */
public enum CacheOutcome {
    HIT,
    DOWNLOADED,
    REPAIRED
}
