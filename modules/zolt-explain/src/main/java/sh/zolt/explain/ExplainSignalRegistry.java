package sh.zolt.explain;

import java.util.Comparator;
import java.util.List;

final class ExplainSignalRegistry {
    private static final List<ExplainSignalDefinition> DEFINITIONS = List.of(
            ExplainSignals.MAVEN_MODULE_MISSING_POM,
            ExplainSignals.MAVEN_PACKAGING_UNSUPPORTED,
            ExplainSignals.MAVEN_DEPENDENCY_DYNAMIC_VERSION,
            ExplainSignals.MAVEN_PLUGIN_LIFECYCLE_BINDING,
            ExplainSignals.MAVEN_PLUGIN_STATIC_SIGNAL,
            ExplainSignals.MAVEN_PLUGIN_EXEC_MAPPABLE,
            ExplainSignals.MAVEN_PLUGIN_EXEC_NONDETERMINISTIC,
            ExplainSignals.MAVEN_PLUGIN_EXEC_UNMAPPABLE,
            ExplainSignals.MAVEN_PROFILE_DETECTED,
            ExplainSignals.MAVEN_KOTLIN_MANUAL_MIGRATION,
            ExplainSignals.MAVEN_LANGUAGE_UNSUPPORTED,
            ExplainSignals.MAVEN_FRAMEWORK_NATIVE_UNSUPPORTED,
            ExplainSignals.MAVEN_REACTOR_DETECTED,
            ExplainSignals.MAVEN_ANNOTATION_PROCESSOR_PATH,
            ExplainSignals.MAVEN_PARENT_SNAPSHOT,
            ExplainSignals.MAVEN_PARENT_UNRESOLVED,
            ExplainSignals.MAVEN_EXTERNAL_PARENT_RESOLVED,
            ExplainSignals.MAVEN_BOM_DETECTED,
            ExplainSignals.MAVEN_DEPENDENCY_UNRESOLVED_VERSION,
            ExplainSignals.MAVEN_DEPENDENCY_MISSING_VERSION,
            ExplainSignals.MAVEN_JAVA_VERSION_UNKNOWN,
            ExplainSignals.MAVEN_JPMS_MODULE_INFO_DETECTED,
            ExplainSignals.MAVEN_TEST_JAVA_VERSION_DIVERGENT,
            ExplainSignals.MAVEN_COMPILER_PLATFORM_API_HOST_CANDIDATE,
            ExplainSignals.MAVEN_REPOSITORY_DECLARED,
            ExplainSignals.MAVEN_REPOSITORY_SNAPSHOTS_ENABLED,
            ExplainSignals.GRADLE_BUILD_SRC_DETECTED,
            ExplainSignals.GRADLE_PROJECT_MISSING_BUILD_FILE,
            ExplainSignals.GRADLE_PROJECT_BUILD_FILE_NAME_UNRESOLVED,
            ExplainSignals.GRADLE_PLUGIN_ALIAS_UNRESOLVED,
            ExplainSignals.GRADLE_KOTLIN_PLUGIN_UNRESOLVED,
            ExplainSignals.GRADLE_PLUGIN_CONVENTION,
            ExplainSignals.GRADLE_SCRIPT_PLUGIN_APPLY_FROM,
            ExplainSignals.GRADLE_PLUGIN_CONDITIONAL_APPLY,
            ExplainSignals.GRADLE_ENVIRONMENT_VARIABLE_READ,
            ExplainSignals.GRADLE_SETTINGS_INCLUDE_CONDITIONAL,
            ExplainSignals.GRADLE_INCLUDED_BUILD_DETECTED,
            ExplainSignals.GRADLE_IMPERATIVE_DEPENDENCY_LOGIC,
            ExplainSignals.GRADLE_DEPENDENCY_DYNAMIC_VERSION,
            ExplainSignals.GRADLE_DEPENDENCY_UNRESOLVED_NOTATION,
            ExplainSignals.GRADLE_CROSS_PROJECT_BUILD_LOGIC,
            ExplainSignals.GRADLE_CUSTOM_TASK_DETECTED,
            ExplainSignals.GRADLE_START_PARAMETER_MUTATION,
            ExplainSignals.GRADLE_TASK_MUTATION_DETECTED,
            ExplainSignals.GRADLE_VERSION_CATALOG_MALFORMED,
            ExplainSignals.GRADLE_VERSION_CATALOG_BUNDLE_UNRESOLVED,
            ExplainSignals.GRADLE_ENTERPRISE_PLUGIN_MAPPED,
            ExplainSignals.GRADLE_REPOSITORY_CREDENTIALS,
            ExplainSignals.GRADLE_REPOSITORY_MAVEN_LOCAL,
            ExplainSignals.GRADLE_DEPENDENCY_POLICY_MUTATION,
            ExplainSignals.GRADLE_OPENAPI_GENERATED_SOURCES,
            ExplainSignals.GRADLE_RESOURCE_FILTERING,
            ExplainSignals.GRADLE_TEST_RUNTIME_SETTINGS,
            ExplainSignals.GRADLE_PACKAGE_ARCHIVE_MUTATION,
            ExplainSignals.GRADLE_PUBLICATION_DETECTED,
            ExplainSignals.GRADLE_KOTLIN_MANUAL_MIGRATION,
            ExplainSignals.GRADLE_LANGUAGE_UNSUPPORTED,
            ExplainSignals.GRADLE_ANDROID_UNSUPPORTED,
            ExplainSignals.GRADLE_FRAMEWORK_NATIVE_UNSUPPORTED,
            ExplainSignals.GRADLE_EXEC_MAPPABLE,
            ExplainSignals.GRADLE_EXEC_UNMAPPABLE,
            ExplainSignals.GRADLE_BOM_DETECTED);
    private static final Comparator<ExplainSignal> COMPARATOR = Comparator
            .comparingInt((ExplainSignal signal) -> severityRank(signal.severity()))
            .thenComparingInt(signal -> categoryRank(signal.category()))
            .thenComparing(ExplainSignal::project)
            .thenComparing(ExplainSignal::id)
            .thenComparing(ExplainSignal::message);

    private ExplainSignalRegistry() {
    }

    static List<ExplainSignalDefinition> definitions() {
        return DEFINITIONS;
    }

    static List<ExplainSignal> sorted(List<ExplainSignal> signals) {
        return signals.stream()
                .sorted(COMPARATOR)
                .toList();
    }

    private static int severityRank(ExplainSignal.Severity severity) {
        return switch (severity) {
            case BLOCK -> 0;
            case UNKNOWN -> 1;
            case WARN -> 2;
            case OK -> 3;
        };
    }

    private static int categoryRank(ExplainSignal.Category category) {
        return switch (category) {
            case BUILDABILITY -> 0;
            case CACHEABILITY -> 1;
            case NON_DETERMINISM -> 2;
            case MIGRATION_BLOCKER -> 3;
        };
    }
}
