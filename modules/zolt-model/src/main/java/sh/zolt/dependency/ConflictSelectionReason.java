package sh.zolt.dependency;

public enum ConflictSelectionReason {
    DIRECT_DEPENDENCY,
    NEWEST_VERSION,
    /**
     * The final materialized graph selected this version after an earlier, now-discarded graph exposed
     * different requests. The conflict entry remains useful audit evidence, but it is not an active
     * version conflict and must not be fed back into mediation or fail-on-conflict policy.
     */
    SELECTED_GRAPH
}
