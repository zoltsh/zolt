package sh.zolt.build.testruntime.execution;

import sh.zolt.test.runtime.TestJvmArguments;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class JunitLauncherClasspath {
    private static final String JBOSS_LOG_MANAGER_PROPERTY =
            "-Djava.util.logging.manager=org.jboss.logmanager.LogManager";

    boolean hasConsoleJar(List<Path> runnerClasspath) {
        return runnerClasspath.stream().anyMatch(JunitLauncherClasspath::isConsoleJar);
    }

    List<Path> launcherClasspath(List<Path> runnerClasspath) {
        List<Path> launcherClasspath = new ArrayList<>();
        boolean hasStandaloneConsole = runnerClasspath.stream().anyMatch(JunitLauncherClasspath::isStandaloneConsoleJar);
        for (Path entry : runnerClasspath) {
            if (hasStandaloneConsole) {
                if (isStandaloneConsoleJar(entry) || isJbossLogManagerJar(entry)) {
                    launcherClasspath.add(entry);
                }
            } else if (isJunitPlatformRuntimeJar(entry)
                    || isJunitPlatformSupportJar(entry)
                    || isJbossLogManagerJar(entry)) {
                launcherClasspath.add(entry);
            }
        }
        return List.copyOf(launcherClasspath);
    }

    List<Path> workerClasspath(List<Path> runnerClasspath) {
        List<Path> result =
                new ArrayList<>(launcherClasspath(runnerClasspath));
        for (Path entry : runnerClasspath) {
            if (!result.contains(entry)) {
                result.add(entry);
            }
        }
        return List.copyOf(result);
    }

    List<String> jvmArguments(
            Path projectDirectory,
            List<Path> runnerClasspath,
            TestJvmArguments testJvmArguments) {
        List<String> arguments = new ArrayList<>();
        arguments.addAll(testJvmArguments.values());
        arguments.add("-Duser.dir=" + projectDirectory.toAbsolutePath().normalize());
        if (runnerClasspath.stream().anyMatch(JunitLauncherClasspath::isJbossLogManagerJar)) {
            arguments.add(JBOSS_LOG_MANAGER_PROPERTY);
        }
        return List.copyOf(arguments);
    }

    private static boolean isConsoleJar(Path path) {
        String name = fileName(path);
        return name.startsWith("junit-platform-console") && name.endsWith(".jar");
    }

    private static boolean isStandaloneConsoleJar(Path path) {
        String name = fileName(path);
        return name.startsWith("junit-platform-console-standalone-") && name.endsWith(".jar");
    }

    private static boolean isJunitPlatformRuntimeJar(Path path) {
        String name = fileName(path);
        return name.startsWith("junit-platform-console-")
                || name.startsWith("junit-platform-reporting-")
                || name.startsWith("junit-platform-launcher-")
                || name.startsWith("junit-platform-engine-")
                || name.startsWith("junit-platform-commons-");
    }

    private static boolean isJunitPlatformSupportJar(Path path) {
        String name = fileName(path);
        return name.startsWith("apiguardian-api-") || name.startsWith("opentest4j-");
    }

    private static boolean isJbossLogManagerJar(Path path) {
        String name = fileName(path);
        return name.startsWith("jboss-logmanager-") && name.endsWith(".jar");
    }

    private static String fileName(Path path) {
        return path.getFileName() == null ? "" : path.getFileName().toString();
    }
}
