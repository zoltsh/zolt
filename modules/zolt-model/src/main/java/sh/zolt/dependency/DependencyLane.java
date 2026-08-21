package sh.zolt.dependency;

/**
 * The lane authored for a direct dependency.
 *
 * <p>This value is intentionally distinct from {@link DependencyScope}, which describes a
 * dependency's resolved classpath and lockfile placement. In particular, API and implementation
 * dependencies may both participate in current-project compilation while remaining different
 * authored lanes for workspace propagation and publication.
 */
public enum DependencyLane {
    API,
    IMPLEMENTATION,
    RUNTIME,
    PROVIDED,
    DEV,
    TEST,
    PROCESSOR,
    TEST_PROCESSOR;

    /** Stable manifest/lock ordering from the frozen dependency section layout. */
    public int canonicalOrder() {
        return switch (this) {
            case IMPLEMENTATION -> 0;
            case API -> 1;
            case RUNTIME -> 2;
            case PROVIDED -> 3;
            case DEV -> 4;
            case TEST -> 5;
            case PROCESSOR -> 6;
            case TEST_PROCESSOR -> 7;
        };
    }
}
