package sh.zolt.explain.gradle;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Static Gradle controls needed to decide whether a Kotlin/JVM draft can be lossless. */
public record GradleKotlinProjectEvidence(
        String javaToolchainVersion,
        boolean javaToolchainShapeProven,
        boolean javaCompatibilityConfigured,
        boolean kotlinExtensionConfigured,
        boolean kotlinCompilerControlsConfigured,
        boolean sourceSetsConfigured,
        String stdlibDefaultDependency,
        List<String> kotlinProperties,
        boolean annotationProcessingConfigured,
        boolean buildSrcPresent) {
    private static final Pattern JAVA_TOOLCHAIN_VERSION = Pattern.compile(
            "(?s)^\\s*languageVersion\\s*(?:=\\s*|\\.set\\s*\\(\\s*)"
                    + "JavaLanguageVersion\\.of\\s*\\(\\s*(\\d+)\\s*\\)\\s*\\)?\\s*;?\\s*$");
    private static final Pattern JAVA_COMPATIBILITY = Pattern.compile(
            "\\b(?:sourceCompatibility|targetCompatibility)\\b");
    private static final Pattern KOTLIN_COMPILER_CONTROLS = Pattern.compile(
            "\\b(?:KotlinCompile|KotlinJvmCompile|compileKotlin|compileTestKotlin|kotlinOptions|compilerOptions)\\b");
    private static final Pattern SOURCE_SETS = Pattern.compile("\\bsourceSets\\b");
    private static final Pattern ANNOTATION_PROCESSING = Pattern.compile(
            "\\b(?:annotationProcessor|testAnnotationProcessor|kapt|ksp)\\b");

    public GradleKotlinProjectEvidence {
        javaToolchainVersion = value(javaToolchainVersion);
        stdlibDefaultDependency = value(stdlibDefaultDependency);
        kotlinProperties = List.copyOf(kotlinProperties);
    }

    static GradleKotlinProjectEvidence inspect(
            String content,
            Map<String, String> rootProperties,
            Map<String, String> projectProperties,
            boolean buildSrcPresent) {
        List<String> toolchains = javaToolchainBlocks(content);
        Optional<String> javaToolchainVersion = toolchains.size() == 1
                ? exactJavaToolchainVersion(toolchains.getFirst())
                : Optional.empty();
        List<String> kotlinProperties = kotlinProperties(rootProperties, projectProperties);
        return new GradleKotlinProjectEvidence(
                javaToolchainVersion.orElseGet(() -> firstJavaToolchainVersion(toolchains)),
                javaToolchainVersion.isPresent(),
                JAVA_COMPATIBILITY.matcher(content).find(),
                !GradleScriptBlocks.topLevelBlocks(content, "kotlin").isEmpty(),
                KOTLIN_COMPILER_CONTROLS.matcher(content).find(),
                SOURCE_SETS.matcher(content).find(),
                GradleProperties.value(
                                "kotlin.stdlib.default.dependency",
                                projectProperties,
                                rootProperties)
                        .orElse(""),
                kotlinProperties,
                ANNOTATION_PROCESSING.matcher(content).find(),
                buildSrcPresent);
    }

    public static GradleKotlinProjectEvidence none() {
        return new GradleKotlinProjectEvidence(
                "", false, false, false, false, false, "", List.of(), false, false);
    }

    private static List<String> javaToolchainBlocks(String content) {
        List<String> blocks = new java.util.ArrayList<>();
        for (String javaBlock : GradleScriptBlocks.topLevelBlocks(content, "java")) {
            blocks.addAll(GradleScriptBlocks.topLevelBlocks(javaBlock, "toolchain"));
        }
        blocks.addAll(GradleScriptBlocks.topLevelBlocks(content, "java.toolchain"));
        return List.copyOf(blocks);
    }

    private static Optional<String> exactJavaToolchainVersion(String block) {
        Matcher matcher = JAVA_TOOLCHAIN_VERSION.matcher(block);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static String firstJavaToolchainVersion(List<String> blocks) {
        Pattern version = Pattern.compile(
                "\\blanguageVersion\\s*(?:=\\s*|\\.set\\s*\\(\\s*)"
                        + "JavaLanguageVersion\\.of\\s*\\(\\s*(\\d+)\\s*\\)");
        for (String block : blocks) {
            Matcher matcher = version.matcher(block);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "";
    }

    private static List<String> kotlinProperties(
            Map<String, String> rootProperties,
            Map<String, String> projectProperties) {
        Set<String> names = new TreeSet<>();
        rootProperties.keySet().stream()
                .filter(name -> name.startsWith("kotlin."))
                .forEach(names::add);
        projectProperties.keySet().stream()
                .filter(name -> name.startsWith("kotlin."))
                .forEach(names::add);
        return List.copyOf(names);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
