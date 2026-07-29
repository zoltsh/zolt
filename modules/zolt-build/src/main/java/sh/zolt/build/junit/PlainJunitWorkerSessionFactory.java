package sh.zolt.build.junit;

import sh.zolt.test.runtime.TestJvmArguments;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface PlainJunitWorkerSessionFactory {
    PlainJunitWorkerSession open(
            Path javaExecutable,
            List<Path> workerClasspath,
            Path projectDirectory,
            List<Path> testRuntimeClasspath,
            TestJvmArguments jvmArguments,
            Map<String, String> environment);

    static PlainJunitWorkerSessionFactory legacy(
            PlainJunitWorkerRunner runner) {
        return (
                javaExecutable,
                workerClasspath,
                projectDirectory,
                testRuntimeClasspath,
                jvmArguments,
                environment) -> new PlainJunitWorkerSession() {
            private int starts;

            @Override
            public PlainJunitWorkerRunResult run(
                    Path testOutputDirectory,
                    sh.zolt.test.TestSelection testSelection,
                    java.util.Optional<Path> reportsDirectory,
                    List<String> events,
                    java.util.Optional<Path> profileDirectory) {
                starts++;
                return runner.run(
                        javaExecutable,
                        workerClasspath,
                        projectDirectory,
                        testRuntimeClasspath,
                        testOutputDirectory,
                        testSelection,
                        jvmArguments,
                        environment,
                        reportsDirectory,
                        events,
                        profileDirectory);
            }

            @Override
            public long startupNanos() {
                return 0L;
            }

            @Override
            public int processStarts() {
                return starts;
            }

            @Override
            public void close() {
            }
        };
    }
}
