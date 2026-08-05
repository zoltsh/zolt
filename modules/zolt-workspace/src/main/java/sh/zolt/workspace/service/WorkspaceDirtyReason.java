package sh.zolt.workspace.service;

/**
 * Why stage-0 planning cannot leave a workspace member alone.
 *
 * <p>Stage 0 runs before any classpath exists, so every reason here is decided from persisted
 * member state, the member's own files, and the root lock — never from a constructed classpath.
 * A reason carries the work it implies: {@link Effect#PIPELINE} needs the canonical member build,
 * {@link Effect#FINALIZE} only needs the clean-member output assurance, and {@link Effect#TEST}
 * concerns the test lanes rather than the main build.
 *
 * <p>There is deliberately no package-lane reason. Packaging re-projects the member's package lock
 * from the root lock every command rather than reading anything stage 0 recorded, and the member's
 * compiled output — the only thing a rebuild would refresh — is covered by the main lane. A reason
 * here would have to be produced and compared to mean anything; adding one that is neither is worse
 * than not having it, so the package lane gets one when it needs one.
 */
enum WorkspaceDirtyReason {
    MISSING_STATE(Effect.PIPELINE),
    CONFIG_CHANGED(Effect.PIPELINE),
    TOOLCHAIN_CHANGED(Effect.PIPELINE),
    MAIN_SOURCE_CHANGED(Effect.PIPELINE),
    GENERATED_SOURCE_CHANGED(Effect.PIPELINE),
    DEPENDENCY_ABI_CHANGED(Effect.PIPELINE),
    RESOLUTION_INPUT_CHANGED(Effect.PIPELINE),
    RESOURCE_CHANGED(Effect.PIPELINE),
    OUTPUT_MISSING(Effect.PIPELINE),
    RESOURCE_OUTPUT_MISSING(Effect.PIPELINE),
    CONSERVATIVE_GENERATED_INPUT(Effect.PIPELINE),
    CONSERVATIVE_FRAMEWORK_OUTPUT(Effect.PIPELINE),
    BUILD_METADATA_REQUIRED(Effect.FINALIZE),
    TEST_SOURCE_CHANGED(Effect.TEST),
    TEST_RESOURCE_CHANGED(Effect.TEST),
    TEST_RESOURCE_OUTPUT_MISSING(Effect.TEST),
    TEST_OUTPUT_MISSING(Effect.TEST);

    /** What the reason asks the executor to do. */
    enum Effect {
        PIPELINE,
        FINALIZE,
        TEST
    }

    private final Effect effect;

    WorkspaceDirtyReason(Effect effect) {
        this.effect = effect;
    }

    boolean requiresPipeline() {
        return effect == Effect.PIPELINE;
    }

    boolean requiresFinalization() {
        return effect == Effect.FINALIZE;
    }

    boolean requiresTestCompile() {
        return effect == Effect.TEST;
    }
}
