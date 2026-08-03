package sh.zolt.junit;

import sh.zolt.test.TestSelection;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class JunitProgrammaticLauncher {
    private final PrintStream out;
    private final ClassLoader classLoader;

    JunitProgrammaticLauncher(PrintStream out, ClassLoader classLoader) {
        this.out = out;
        this.classLoader = classLoader;
    }

    int execute(
            Path testOutputDirectory,
            TestSelection testSelection,
            Optional<Path> reportsDirectory,
            Optional<Path> profileDirectory,
            List<String> events) throws ReflectiveOperationException, IOException {
        Class<?> listenerClass = load("org.junit.platform.launcher.listeners.SummaryGeneratingListener");
        Object listener = listenerClass.getDeclaredConstructor().newInstance();
        Object request = discoveryRequest(
                testOutputDirectory.toAbsolutePath().normalize(),
                testSelection);
        Object session = openSession();
        Class<?> sessionInterface = load("org.junit.platform.launcher.LauncherSession");
        JunitTestProfileCollector profileCollector =
                profileDirectory == null || profileDirectory.isEmpty()
                        ? null
                        : new JunitTestProfileCollector(profileDirectory.orElseThrow());
        try {
            Object launcher = sessionInterface.getMethod("getLauncher").invoke(session);
            Class<?> launcherInterface = load("org.junit.platform.launcher.Launcher");
            Class<?> requestInterface = load("org.junit.platform.launcher.LauncherDiscoveryRequest");
            Class<?> listenerInterface = load("org.junit.platform.launcher.TestExecutionListener");
            List<Object> listeners = listeners(
                    listener,
                    listenerInterface,
                    reportsDirectory,
                    profileCollector);
            Method execute = launcherInterface.getMethod(
                    "execute",
                    requestInterface,
                    Array.newInstance(listenerInterface, 0).getClass());
            Object listenerArray = Array.newInstance(listenerInterface, listeners.size());
            for (int index = 0; index < listeners.size(); index++) {
                Array.set(listenerArray, index, listeners.get(index));
            }
            execute.invoke(launcher, request, listenerArray);
        } finally {
            try {
                sessionInterface.getMethod("close").invoke(session);
            } finally {
                if (profileCollector != null) {
                    profileCollector.write();
                }
            }
        }
        return summarize(listenerClass, listener);
    }

    private List<Object> listeners(
            Object summaryListener,
            Class<?> listenerInterface,
            Optional<Path> reportsDirectory,
            JunitTestProfileCollector profileCollector)
            throws ReflectiveOperationException, IOException {
        List<Object> listeners = new ArrayList<>();
        listeners.add(summaryListener);
        if (reportsDirectory != null && reportsDirectory.isPresent()) {
            listeners.add(reportListener(reportsDirectory.orElseThrow()));
        }
        if (profileCollector != null) {
            listeners.add(profileCollector.listener(listenerInterface));
        }
        return listeners;
    }

    private Object openSession() throws ReflectiveOperationException {
        Class<?> launcherFactoryClass = load(
                "org.junit.platform.launcher.core.LauncherFactory");
        try {
            Class<?> launcherConfigClass = load(
                    "org.junit.platform.launcher.core.LauncherConfig");
            Object builder = launcherConfigClass.getMethod("builder").invoke(null);
            disableAutoRegistration(
                    builder,
                    "enableLauncherSessionListenerAutoRegistration");
            disableAutoRegistration(
                    builder,
                    "enableLauncherDiscoveryListenerAutoRegistration");
            disableAutoRegistration(
                    builder,
                    "enableTestExecutionListenerAutoRegistration");
            disableAutoRegistration(
                    builder,
                    "enablePostDiscoveryFilterAutoRegistration");
            Method build = builder.getClass().getMethod("build");
            build.setAccessible(true);
            Object launcherConfig = build.invoke(builder);
            return launcherFactoryClass
                    .getMethod("openSession", launcherConfigClass)
                    .invoke(null, launcherConfig);
        } catch (ClassNotFoundException | NoSuchMethodException exception) {
            return launcherFactoryClass.getMethod("openSession").invoke(null);
        }
    }

    private static void disableAutoRegistration(
            Object builder,
            String methodName) throws ReflectiveOperationException {
        Method method = builder.getClass().getMethod(methodName, boolean.class);
        method.setAccessible(true);
        method.invoke(builder, false);
    }

    private Object reportListener(Path reportsDirectory)
            throws ReflectiveOperationException, IOException {
        Path normalizedDirectory =
                reportsDirectory.toAbsolutePath().normalize();
        Files.createDirectories(normalizedDirectory);
        Class<?> listenerClass = load(
                "org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener");
        return listenerClass
                .getConstructor(Path.class, PrintWriter.class)
                .newInstance(
                        normalizedDirectory,
                        new PrintWriter(out, true));
    }

    private Object discoveryRequest(
            Path testOutputDirectory,
            TestSelection testSelection) throws ReflectiveOperationException {
        TestSelection selection =
                testSelection == null ? TestSelection.empty() : testSelection;
        Class<?> selectorsClass = load(
                "org.junit.platform.engine.discovery.DiscoverySelectors");
        Object selectors = selectors(
                selection,
                selectorsClass,
                testOutputDirectory);
        Class<?> builderClass = load(
                "org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder");
        Object builder = builderClass.getMethod("request").invoke(null);
        builderClass.getMethod("selectors", List.class)
                .invoke(builder, selectors);
        applyFilters(builderClass, builder, selection);
        return builderClass.getMethod("build").invoke(builder);
    }

    private static Object selectors(
            TestSelection selection,
            Class<?> selectorsClass,
            Path testOutputDirectory) throws ReflectiveOperationException {
        boolean explicit = !selection.classSelectors().isEmpty()
                || !selection.methodSelectors().isEmpty();
        if (!explicit) {
            return selectorsClass
                    .getMethod("selectClasspathRoots", Set.class)
                    .invoke(null, Set.of(testOutputDirectory));
        }
        List<Object> selectors = new ArrayList<>();
        Method selectClass =
                selectorsClass.getMethod("selectClass", String.class);
        Method selectMethod =
                selectorsClass.getMethod("selectMethod", String.class);
        for (String classSelector : selection.classSelectors()) {
            selectors.add(selectClass.invoke(null, classSelector));
        }
        for (TestSelection.MethodSelector method : selection.methodSelectors()) {
            selectors.add(selectMethod.invoke(
                    null,
                    method.className() + "#" + method.methodName()));
        }
        return selectors;
    }

    private void applyFilters(
            Class<?> builderClass,
            Object builder,
            TestSelection selection) throws ReflectiveOperationException {
        List<Object> filters = new ArrayList<>();
        addClassNameFilters(filters, selection);
        addTagFilters(filters, selection);
        if (filters.isEmpty()) {
            return;
        }
        Class<?> filterInterface =
                load("org.junit.platform.engine.Filter");
        Object filterArray = Array.newInstance(
                filterInterface,
                filters.size());
        for (int index = 0; index < filters.size(); index++) {
            Array.set(filterArray, index, filters.get(index));
        }
        builderClass.getMethod("filters", filterArray.getClass())
                .invoke(builder, filterArray);
    }

    private void addClassNameFilters(
            List<Object> filters,
            TestSelection selection) throws ReflectiveOperationException {
        Class<?> filterClass = load(
                "org.junit.platform.engine.discovery.ClassNameFilter");
        List<String> patterns = selection.classNameRegexPatterns();
        if (patterns.isEmpty()
                && selection.classSelectors().isEmpty()
                && selection.methodSelectors().isEmpty()) {
            patterns = TestSelection.defaultScanClassNamePatterns();
        }
        if (!patterns.isEmpty()) {
            filters.add(filterClass
                    .getMethod(
                            "includeClassNamePatterns",
                            String[].class)
                    .invoke(null, (Object) patterns.toArray(String[]::new)));
        }
    }

    private void addTagFilters(
            List<Object> filters,
            TestSelection selection) throws ReflectiveOperationException {
        Class<?> tagFilterClass =
                load("org.junit.platform.launcher.TagFilter");
        if (!selection.includedTags().isEmpty()) {
            filters.add(tagFilterClass
                    .getMethod("includeTags", String[].class)
                    .invoke(
                            null,
                            (Object) selection.includedTags()
                                    .toArray(String[]::new)));
        }
        if (!selection.excludedTags().isEmpty()) {
            filters.add(tagFilterClass
                    .getMethod("excludeTags", String[].class)
                    .invoke(
                            null,
                            (Object) selection.excludedTags()
                                    .toArray(String[]::new)));
        }
    }

    private int summarize(Class<?> listenerClass, Object listener)
            throws ReflectiveOperationException {
        Object summary =
                listenerClass.getMethod("getSummary").invoke(listener);
        Class<?> summaryClass = load(
                "org.junit.platform.launcher.listeners.TestExecutionSummary");
        long found =
                (long) summaryClass.getMethod("getTestsFoundCount").invoke(summary);
        long succeeded = (long) summaryClass
                .getMethod("getTestsSucceededCount")
                .invoke(summary);
        long failed = (long) summaryClass
                .getMethod("getTestsFailedCount")
                .invoke(summary);
        long aborted = (long) summaryClass
                .getMethod("getTestsAbortedCount")
                .invoke(summary);
        long totalFailures = (long) summaryClass
                .getMethod("getTotalFailureCount")
                .invoke(summary);
        out.println("Tests found: " + found);
        out.println("Tests succeeded: " + succeeded);
        out.println("Tests failed: " + failed);
        if (totalFailures > 0) {
            out.println();
            summaryClass
                    .getMethod("printFailuresTo", PrintWriter.class)
                    .invoke(summary, new PrintWriter(out, true));
        }
        return found == 0 ? 2 : failed == 0 && aborted == 0 ? 0 : 1;
    }

    private Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, true, classLoader);
    }
}
