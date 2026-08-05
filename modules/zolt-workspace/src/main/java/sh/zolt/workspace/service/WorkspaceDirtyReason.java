package sh.zolt.workspace.service;

/**
 * Why stage-0 planning cannot leave a workspace member alone.
 *
 * <p>Stage 0 runs before any classpath exists, so every reason here is decided from persisted
 * member state, the member's own files, and the root lock — never from a constructed classpath.
 * A reason carries the work it implies: {@link Effect#PIPELINE} needs the canonical member build,
 * {@link Effect#FINALIZE} only needs the clean-member output assurance, and {@link Effect#TEST}
 * concerns the test lanes rather than the main build.
 */
enum WorkspaceDirtyReason {
    MISSING_STATE("missing-workspace-state", Effect.PIPELINE),
    CONFIG_CHANGED("config-changed", Effect.PIPELINE),
    TOOLCHAIN_CHANGED("toolchain-changed", Effect.PIPELINE),
    MAIN_SOURCE_CHANGED("main-source-changed", Effect.PIPELINE),
    GENERATED_SOURCE_CHANGED("generated-source-changed", Effect.PIPELINE),
    DEPENDENCY_ABI_CHANGED("dependency-abi-changed", Effect.PIPELINE),
    RESOLUTION_INPUT_CHANGED("resolution-input-changed", Effect.PIPELINE),
    RESOURCE_CHANGED("resource-changed", Effect.PIPELINE),
    OUTPUT_MISSING("main-output-missing-or-stale", Effect.PIPELINE),
    RESOURCE_OUTPUT_MISSING("resource-output-missing-or-stale", Effect.PIPELINE),
    CONSERVATIVE_GENERATED_INPUT("conservative-generated-input", Effect.PIPELINE),
    CONSERVATIVE_FRAMEWORK_OUTPUT("conservative-framework-output", Effect.PIPELINE),
    BUILD_METADATA_REQUIRED("build-metadata-required", Effect.FINALIZE),
    TEST_SOURCE_CHANGED("test-source-changed", Effect.TEST),
    TEST_RESOURCE_CHANGED("test-resource-changed", Effect.TEST),
    TEST_RESOURCE_OUTPUT_MISSING("test-resource-output-missing-or-stale", Effect.TEST),
    TEST_OUTPUT_MISSING("test-output-missing", Effect.TEST),
    PACKAGE_OUTPUT_MISSING("package-output-missing", Effect.PIPELINE);

    /** What the reason asks the executor to do. */
    enum Effect {
        PIPELINE,
        FINALIZE,
        TEST
    }

    private final String wireName;
    private final Effect effect;

    WorkspaceDirtyReason(String wireName, Effect effect) {
        this.wireName = wireName;
        this.effect = effect;
    }

    String wireName() {
        return wireName;
    }

    Effect effect() {
        return effect;
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
