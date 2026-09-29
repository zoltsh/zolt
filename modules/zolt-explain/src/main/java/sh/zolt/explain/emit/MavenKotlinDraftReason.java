package sh.zolt.explain.emit;

/** Maven-specific evidence that prevents lossless Kotlin/JVM draft emission. */
enum MavenKotlinDraftReason {
    PARENT_MODEL,
    PROFILE_MODEL,
    PACKAGING,
    ACTIVE_PLUGIN_COUNT,
    PLUGIN_MANAGEMENT,
    OTHER_KOTLIN_PLUGIN,
    GROOVY_PLUGIN,
    UNSUPPORTED_LANGUAGE_PLUGIN,
    BUILD_HELPER_PLUGIN,
    OTHER_LIFECYCLE_EXTENSION,
    ANNOTATION_PROCESSOR,
    JAVAC_PROCESSOR_DISCOVERY,
    MAVEN_TOOLCHAIN,
    COMPILER_PLUGIN_CONTROLS,
    COMPILER_PROPERTIES,
    SOURCE_ENCODING,
    GROOVY_TEST_SOURCES,
    MODULAR_SOURCES,
    SOURCE_LINKS,
    SOURCE_ROOT_SYNTAX,
    ARTIFACT_ID,
    GENERATED_SOURCES,
    PLUGIN_SHAPE,
    KAPT_COMPILE_CLASSPATH,
    COMPILER_TARGET,
    EXPLICIT_TEST_ROOT,
    STDLIB_SHAPE;

    String description() {
        return switch (this) {
            case PARENT_MODEL -> "an inherited Maven parent could change plugin behavior";
            case PROFILE_MODEL -> "a Maven profile could change plugin behavior";
            case PACKAGING -> "the project was not a plain JAR";
            case ACTIVE_PLUGIN_COUNT -> "exactly one active Kotlin Maven plugin was not found";
            case PLUGIN_MANAGEMENT -> "matching Maven pluginManagement requires effective-model merging";
            case OTHER_KOTLIN_PLUGIN -> "another Kotlin build plugin was present";
            case GROOVY_PLUGIN -> "the project also applied gmavenplus";
            case UNSUPPORTED_LANGUAGE_PLUGIN -> "another unsupported language compiler plugin was applied";
            case BUILD_HELPER_PLUGIN -> "build-helper source-root behavior was present";
            case OTHER_LIFECYCLE_EXTENSION -> "another Maven lifecycle extension could replace Kotlin bindings";
            case ANNOTATION_PROCESSOR -> "Maven annotation processor paths were present";
            case JAVAC_PROCESSOR_DISCOVERY -> "javac classpath annotation processor discovery was not explicitly disabled";
            case MAVEN_TOOLCHAIN -> "a Maven JDK toolchain could change the compiler used by Kotlin";
            case COMPILER_PLUGIN_CONTROLS -> "a plain fixed maven-compiler-plugin 3.13+ was not proven";
            case COMPILER_PROPERTIES -> "unsupported Maven compiler-control properties were present";
            case SOURCE_ENCODING -> "Maven source encoding was not explicitly UTF-8";
            case GROOVY_TEST_SOURCES -> "the project also contained conventional Groovy test sources";
            case MODULAR_SOURCES -> "module-info.java requires unsupported Kotlin JPMS compilation";
            case SOURCE_LINKS -> "a Maven source root contained symbolic links with different traversal semantics";
            case SOURCE_ROOT_SYNTAX -> "a Maven source root could not be represented without changing its path";
            case ARTIFACT_ID -> "the effective Maven artifactId was not statically proven as a safe Kotlin module name";
            case GENERATED_SOURCES -> "generated source or resource steps were present";
            case PLUGIN_SHAPE -> "the Kotlin Maven plugin carried unsupported controls";
            case KAPT_COMPILE_CLASSPATH -> "KAPT compile-classpath processor discovery was not explicitly disabled";
            case COMPILER_TARGET -> "Kotlin 2.4+ extension target alignment lacked an explicit maven.compiler.release";
            case EXPLICIT_TEST_ROOT -> "Maven replaced rather than extended the test source root";
            case STDLIB_SHAPE -> "the Maven kotlin-stdlib declaration was not a direct plain JAR";
        };
    }
}
