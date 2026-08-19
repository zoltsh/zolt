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
    TEST_PROCESSOR
}
