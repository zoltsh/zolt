package sh.zolt.sbom;

import sh.zolt.dependency.DependencyScope;

/**
 * Groups the resolver's {@link DependencyScope}s into the SBOM inclusion buckets from the design
 * record. {@link #REQUIRED} components are always emitted (CycloneDX {@code required}); every other
 * bucket is opt-in via a {@code --include-*} flag and is emitted as {@code optional}.
 */
public enum SbomScopeGroup {
    REQUIRED(SbomComponentScope.REQUIRED),
    PROVIDED(SbomComponentScope.OPTIONAL),
    DEV(SbomComponentScope.OPTIONAL),
    TEST(SbomComponentScope.OPTIONAL),
    TOOLS(SbomComponentScope.OPTIONAL);

    private final SbomComponentScope componentScope;

    SbomScopeGroup(SbomComponentScope componentScope) {
        this.componentScope = componentScope;
    }

    public SbomComponentScope componentScope() {
        return componentScope;
    }

    public static SbomScopeGroup of(DependencyScope scope) {
        return switch (scope) {
            case COMPILE, RUNTIME -> REQUIRED;
            case PROVIDED -> PROVIDED;
            case DEV -> DEV;
            case TEST, TEST_PROCESSOR -> TEST;
            case PROCESSOR,
                    QUARKUS_DEPLOYMENT,
                    TOOL_SPRING_AOT,
                    TOOL_OPENAPI,
                    TOOL_PROTOBUF,
                    TOOL_EXEC,
                    TOOL_COVERAGE,
                    TOOL_GROOVY -> TOOLS;
        };
    }
}
