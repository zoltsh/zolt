package sh.zolt.explain.emit;

/** Gradle-specific evidence that prevents lossless Kotlin/JVM draft emission. */
enum GradleKotlinDraftReason {
    ROOT_PROJECT,
    PROJECT_NAME,
    SETTINGS_SHAPE,
    ACTIVE_PLUGIN_COUNT,
    PLUGIN_BLOCK,
    OTHER_KOTLIN_PLUGIN,
    OTHER_APPLIED_PLUGIN,
    PLUGIN_VERSION,
    JAVA_TOOLCHAIN,
    JAVA_COMPATIBILITY,
    KOTLIN_EXTENSION,
    KOTLIN_COMPILER_CONTROLS,
    KOTLIN_PROPERTIES,
    SOURCE_SETS,
    ANNOTATION_PROCESSING,
    BUILD_SRC,
    BUILD_SHAPE,
    GROOVY_SOURCES,
    MODULAR_SOURCES,
    SOURCE_LINKS,
    JAVA_SOURCES,
    PLATFORM_DEPENDENCY,
    DEPENDENCY_RESOLUTION,
    STDLIB_DEFAULT,
    STDLIB_SHAPE;

    String description() {
        return switch (this) {
            case ROOT_PROJECT -> "the project was not a standalone Gradle root";
            case PROJECT_NAME -> "the literal Gradle project name was not a safe Kotlin module name";
            case SETTINGS_SHAPE -> "Gradle settings contained build logic beyond one literal root name";
            case ACTIVE_PLUGIN_COUNT -> "exactly one applied org.jetbrains.kotlin.jvm plugin was not found";
            case PLUGIN_BLOCK -> "the Gradle plugins block was not a fully literal supported shape";
            case OTHER_KOTLIN_PLUGIN -> "another applied Kotlin compiler plugin was present";
            case OTHER_APPLIED_PLUGIN -> "another applied plugin could alter compilation";
            case PLUGIN_VERSION -> "the Kotlin Gradle plugin was not a fixed qualified 2.2 or 2.3 release";
            case JAVA_TOOLCHAIN -> "one literal Java 8-21 toolchain aligned with the project release was not proven";
            case JAVA_COMPATIBILITY -> "separate Java source or target compatibility controls were present";
            case KOTLIN_EXTENSION -> "a Kotlin extension block could override compiler behavior";
            case KOTLIN_COMPILER_CONTROLS -> "Kotlin compiler task or option controls were present";
            case KOTLIN_PROPERTIES -> "unsupported Kotlin Gradle properties were present";
            case SOURCE_SETS -> "Gradle source-set configuration could change the compilation inputs";
            case ANNOTATION_PROCESSING -> "Gradle annotation processing or symbol processing was configured";
            case BUILD_SRC -> "buildSrc could configure Kotlin compilation outside the inspected build file";
            case BUILD_SHAPE -> "executable or unrecognized top-level Gradle build logic was present";
            case GROOVY_SOURCES -> "the project also contained conventional Groovy test sources";
            case MODULAR_SOURCES -> "module-info.java requires unsupported Kotlin JPMS compilation";
            case SOURCE_LINKS -> "a Gradle source root contained symbolic links with different traversal semantics";
            case JAVA_SOURCES -> "mixed Java sources require Gradle javac controls that were not proven equivalent";
            case PLATFORM_DEPENDENCY -> "a Gradle platform could override the Kotlin runtime selected by the plugin";
            case DEPENDENCY_RESOLUTION -> "Gradle dependency-resolution rules could replace the Kotlin runtime";
            case STDLIB_DEFAULT -> "automatic kotlin-stdlib injection was disabled or configured ambiguously";
            case STDLIB_SHAPE -> "the Gradle kotlin-stdlib declaration was not the ordinary JVM artifact";
        };
    }
}
