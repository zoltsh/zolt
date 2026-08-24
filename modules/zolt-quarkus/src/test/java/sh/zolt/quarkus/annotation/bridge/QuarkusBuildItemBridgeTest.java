package sh.zolt.quarkus.annotation.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.quarkus.annotation.QuarkusAnnotationProgrammaticRunner;
import sh.zolt.quarkus.testsupport.QuarkusProvidedRuntime;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class QuarkusBuildItemBridgeTest {
    private static final String DIAGNOSTIC_FILE_PROPERTY =
            "zolt.quarkus.test-class-bean-diagnostic-file";

    @TempDir
    private Path tempDir;

    @Test
    void resolvesOptionalBuildItemsThroughBuildChainClassLoader() throws Exception {
        try (URLClassLoader runtime = QuarkusProvidedRuntime.open()) {
            Object builder = builderWithClassLoader(runtime, runtime);

            assertEquals(
                    "io.quarkus.deployment.builditem.CombinedIndexBuildItem",
                    resolvedClassName(
                            runtime,
                            "sh.zolt.quarkus.annotation.bridge.QuarkusCombinedIndexBuildItemClass",
                            builder));
            assertEquals(
                    "io.quarkus.deployment.builditem.ApplicationArchivesBuildItem",
                    resolvedClassName(
                            runtime,
                            "sh.zolt.quarkus.annotation.bridge.QuarkusApplicationArchivesBuildItemClass",
                            builder));
        }
    }

    @Test
    void reportsMissingOptionalBuildItemClassesDeterministically() throws Exception {
        Path diagnosticFile = tempDir.resolve("diagnostics/bridge.txt");
        try (URLClassLoader runtime = QuarkusProvidedRuntime.open()) {
            Object builder = builderWithClassLoader(runtime, new ClassLoader(null) {
            });
            String previousDiagnostic = System.getProperty(DIAGNOSTIC_FILE_PROPERTY);
            try {
                System.setProperty(DIAGNOSTIC_FILE_PROPERTY, diagnosticFile.toString());

                assertTrue(resolve(
                                runtime,
                                "sh.zolt.quarkus.annotation.bridge.QuarkusCombinedIndexBuildItemClass",
                                builder)
                        .isEmpty());
                assertTrue(resolve(
                                runtime,
                                "sh.zolt.quarkus.annotation.bridge.QuarkusApplicationArchivesBuildItemClass",
                                builder)
                        .isEmpty());
            } finally {
                restore(DIAGNOSTIC_FILE_PROPERTY, previousDiagnostic);
            }
        }

        String diagnostic = Files.readString(diagnosticFile);
        assertTrue(diagnostic.contains("combinedIndexBuildItem.available=false"), diagnostic);
        assertTrue(diagnostic.contains("combinedIndexBuildItem.reason=ClassNotFoundException"), diagnostic);
        assertTrue(diagnostic.contains("applicationArchivesBuildItem.available=false"), diagnostic);
        assertTrue(diagnostic.contains("applicationArchivesBuildItem.reason=ClassNotFoundException"), diagnostic);
    }

    @Test
    void appliesOptionalConsumeFlagWhenSupported() throws Exception {
        try (URLClassLoader runtime = QuarkusProvidedRuntime.open()) {
            Path diagnosticFile = tempDir.resolve("diagnostics/optional-consume.txt");
            Object builder = buildChainClass(runtime).getMethod("builder").invoke(null);
            Object step = buildChainBuilderClass(runtime).getMethod("addBuildStep").invoke(builder);
            Optional<Class<?>> combinedIndex = Optional.of(Class.forName(
                    "io.quarkus.deployment.builditem.CombinedIndexBuildItem",
                    false,
                    runtime));
            String previousDiagnostic = System.getProperty(DIAGNOSTIC_FILE_PROPERTY);
            Object returned;
            try {
                System.setProperty(DIAGNOSTIC_FILE_PROPERTY, diagnosticFile.toString());

                returned = Class.forName(
                                "sh.zolt.quarkus.annotation.bridge.QuarkusOptionalBuildItemConsumes",
                                false,
                                runtime)
                        .getMethod("optional", buildStepBuilderClass(runtime), List.class)
                        .invoke(null, step, List.of(Optional.empty(), combinedIndex));
            } finally {
                restore(DIAGNOSTIC_FILE_PROPERTY, previousDiagnostic);
            }

            assertSame(step, returned);
            assertTrue(optionalConsumes(step).contains("OPTIONAL"));
            assertTrue(Files.notExists(diagnosticFile));
        }
    }

    @Test
    void reportsDeterministicFallbackWhenConsumeFlagsCannotBeLoaded() throws Exception {
        try (URLClassLoader runtime = QuarkusProvidedRuntime.open()) {
            Path diagnosticFile = tempDir.resolve("diagnostics/optional-consume-fallback.txt");
            Object builder = buildChainClass(runtime).getMethod("builder").invoke(null);
            Object step = buildChainBuilderClass(runtime).getMethod("addBuildStep").invoke(builder);
            Class<?> combinedIndex = Class.forName(
                    "io.quarkus.deployment.builditem.CombinedIndexBuildItem",
                    false,
                    runtime);
            ClassLoader missingFlags = new ClassLoader(runtime) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if ("io.quarkus.builder.ConsumeFlag".equals(name)) {
                        throw new ClassNotFoundException(name);
                    }
                    return super.loadClass(name, resolve);
                }
            };
            String previousDiagnostic = System.getProperty(DIAGNOSTIC_FILE_PROPERTY);
            Object returned;
            try {
                System.setProperty(DIAGNOSTIC_FILE_PROPERTY, diagnosticFile.toString());

                Method optional = Class.forName(
                                "sh.zolt.quarkus.annotation.bridge.QuarkusOptionalBuildItemConsumes",
                                false,
                                runtime)
                        .getDeclaredMethod(
                                "optional",
                                buildStepBuilderClass(runtime),
                                Class.class,
                                ClassLoader.class);
                optional.setAccessible(true);
                returned = optional.invoke(null, step, combinedIndex, missingFlags);
            } finally {
                restore(DIAGNOSTIC_FILE_PROPERTY, previousDiagnostic);
            }

            assertSame(step, returned);
            String diagnostic = Files.readString(diagnosticFile);
            assertTrue(diagnostic.contains("CombinedIndexBuildItem.optionalConsume=false"), diagnostic);
            assertTrue(diagnostic.contains("CombinedIndexBuildItem.optionalConsumeReason=ClassNotFoundException"),
                    diagnostic);
        }
    }

    @Test
    void buildsAdditionalApplicationArchiveBuildItemWithPathCollectionBridge() throws Exception {
        try (URLClassLoader runtime = QuarkusProvidedRuntime.open()) {
            Path testOutput = tempDir.resolve("target/test-classes");
            Files.createDirectories(testOutput);
            Class<?> bridgeClass = Class.forName(
                    "sh.zolt.quarkus.annotation.bridge.QuarkusAdditionalApplicationArchiveBuildItemBridge",
                    false,
                    runtime);
            Method method = bridgeClass.getDeclaredMethod(
                    "testOutputArchiveBuildItem",
                    Class.class,
                    Path.class);
            method.setAccessible(true);
            Class<?> archiveItemClass = Class.forName(
                    "io.quarkus.deployment.builditem.AdditionalApplicationArchiveBuildItem",
                    false,
                    runtime);

            Object item = method.invoke(null, archiveItemClass, testOutput);

            assertTrue(archiveItemClass.isInstance(item));
            Object resolvedPaths = archiveItemClass.getMethod("getResolvedPaths").invoke(item);
            assertTrue(containsPath((Iterable<?>) resolvedPaths, testOutput));
        }
    }

    @Test
    void addTestOutputArchiveReportsMissingOutputBeforeRegisteringStep() throws Exception {
        Path diagnosticFile = tempDir.resolve("diagnostics/missing-output.txt");
        try (URLClassLoader runtime = QuarkusProvidedRuntime.open()) {
            Object builder = builderWithClassLoader(runtime, runtime);
            String previousDiagnostic = System.getProperty(DIAGNOSTIC_FILE_PROPERTY);
            String previousTestOutput =
                    System.getProperty(QuarkusAnnotationProgrammaticRunner.TEST_OUTPUT_DIRECTORY_PROPERTY);
            try {
                System.setProperty(DIAGNOSTIC_FILE_PROPERTY, diagnosticFile.toString());
                System.clearProperty(QuarkusAnnotationProgrammaticRunner.TEST_OUTPUT_DIRECTORY_PROPERTY);

                addTestOutputArchive(runtime, builder);
            } finally {
                restore(DIAGNOSTIC_FILE_PROPERTY, previousDiagnostic);
                restore(QuarkusAnnotationProgrammaticRunner.TEST_OUTPUT_DIRECTORY_PROPERTY, previousTestOutput);
            }
        }

        String diagnostic = Files.readString(diagnosticFile);
        assertTrue(diagnostic.contains("testOutputArchiveBuildItem.available=true"), diagnostic);
        assertTrue(diagnostic.contains("testOutputArchiveBuildItem.reason=missing-test-output"), diagnostic);
    }

    @Test
    void addTestOutputArchiveReportsMissingBuildItemWhenOutputExists() throws Exception {
        Path testOutput = tempDir.resolve("target/test-classes");
        Path diagnosticFile = tempDir.resolve("diagnostics/missing-build-item.txt");
        Files.createDirectories(testOutput);
        try (URLClassLoader runtime = QuarkusProvidedRuntime.open()) {
            Object builder = builderWithClassLoader(runtime, new ClassLoader(null) {
            });
            String previousDiagnostic = System.getProperty(DIAGNOSTIC_FILE_PROPERTY);
            String previousTestOutput =
                    System.getProperty(QuarkusAnnotationProgrammaticRunner.TEST_OUTPUT_DIRECTORY_PROPERTY);
            try {
                System.setProperty(DIAGNOSTIC_FILE_PROPERTY, diagnosticFile.toString());
                System.setProperty(
                        QuarkusAnnotationProgrammaticRunner.TEST_OUTPUT_DIRECTORY_PROPERTY,
                        testOutput.toString());

                addTestOutputArchive(runtime, builder);
            } finally {
                restore(DIAGNOSTIC_FILE_PROPERTY, previousDiagnostic);
                restore(QuarkusAnnotationProgrammaticRunner.TEST_OUTPUT_DIRECTORY_PROPERTY, previousTestOutput);
            }
        }

        String diagnostic = Files.readString(diagnosticFile);
        assertTrue(diagnostic.contains("testOutputArchiveBuildItem.reason=ClassNotFoundException"), diagnostic);
        assertTrue(diagnostic.contains("testOutputArchiveBuildItem.reason=missing-build-item"), diagnostic);
    }

    @Test
    void setsDefaultScopeUsingJandexDotNameWhenAvailable() throws Exception {
        try (URLClassLoader runtime = QuarkusProvidedRuntime.open()) {
            Object builder = Class.forName(
                            "sh.zolt.quarkus.annotation.bridge.QuarkusBuildItemBridgeTest$DotNameScopeBuilder",
                            true,
                            runtime)
                    .getConstructor()
                    .newInstance();

            Class.forName("sh.zolt.quarkus.annotation.bridge.QuarkusAdditionalBeanBuildItemBridge", false, runtime)
                    .getMethod("setBuilderDefaultScope", Object.class, String.class)
                    .invoke(null, builder, "jakarta.enterprise.context.Dependent");

            Field defaultScope = builder.getClass().getDeclaredField("defaultScope");
            defaultScope.setAccessible(true);
            Object value = defaultScope.get(builder);
            assertEquals("org.jboss.jandex.DotName", value.getClass().getName());
            assertEquals("jakarta.enterprise.context.Dependent", value.toString());
        }
    }

    private static Object builderWithClassLoader(URLClassLoader runtime, ClassLoader classLoader) throws Exception {
        Object builder = buildChainClass(runtime).getMethod("builder").invoke(null);
        buildChainBuilderClass(runtime).getMethod("setClassLoader", ClassLoader.class).invoke(builder, classLoader);
        return builder;
    }

    @SuppressWarnings("unchecked")
    private static Optional<Class<?>> resolve(
            URLClassLoader runtime,
            String bridgeClassName,
            Object builder)
            throws Exception {
        return (Optional<Class<?>>) Class.forName(bridgeClassName, false, runtime)
                .getMethod("resolve", buildChainBuilderClass(runtime))
                .invoke(null, builder);
    }

    private static String resolvedClassName(URLClassLoader runtime, String bridgeClassName, Object builder)
            throws Exception {
        return resolve(runtime, bridgeClassName, builder).orElseThrow().getName();
    }

    private static void addTestOutputArchive(URLClassLoader runtime, Object builder) throws Exception {
        Class.forName(
                        "sh.zolt.quarkus.annotation.bridge.QuarkusAdditionalApplicationArchiveBuildItemBridge",
                        false,
                        runtime)
                .getMethod("addTestOutputArchive", buildChainBuilderClass(runtime))
                .invoke(null, builder);
    }

    private static boolean containsPath(Iterable<?> paths, Path expected) {
        Path normalized = expected.toAbsolutePath().normalize();
        for (Object entry : paths) {
            if (entry instanceof Path path && normalized.equals(path.toAbsolutePath().normalize())) {
                return true;
            }
        }
        return false;
    }

    private static void restore(String property, String previous) {
        if (previous == null) {
            System.clearProperty(property);
            return;
        }
        System.setProperty(property, previous);
    }

    private static String optionalConsumes(Object step) throws Exception {
        Method getConsumes = step.getClass().getDeclaredMethod("getConsumes");
        getConsumes.setAccessible(true);
        Map<?, ?> consumes = (Map<?, ?>) getConsumes.invoke(step);
        assertEquals(1, consumes.size());
        Object consume = consumes.values().iterator().next();
        Method flags = consume.getClass().getMethod("flags");
        flags.setAccessible(true);
        return flags.invoke(consume).toString();
    }

    public static final class DotNameScopeBuilder {
        private Object defaultScope;

        public DotNameScopeBuilder setDefaultScope(Object defaultScope) {
            this.defaultScope = defaultScope;
            return this;
        }
    }

    private static Class<?> buildChainClass(ClassLoader runtime) throws ClassNotFoundException {
        return Class.forName("io.quarkus.builder.BuildChain", false, runtime);
    }

    private static Class<?> buildChainBuilderClass(ClassLoader runtime) throws ClassNotFoundException {
        return Class.forName("io.quarkus.builder.BuildChainBuilder", false, runtime);
    }

    private static Class<?> buildStepBuilderClass(ClassLoader runtime) throws ClassNotFoundException {
        return Class.forName("io.quarkus.builder.BuildStepBuilder", false, runtime);
    }
}
