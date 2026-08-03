package sh.zolt.build.testruntime;

import sh.zolt.build.junit.PlainJunitWorkerPoolRunner;
import sh.zolt.build.junit.PlainJunitWorkerProcessRunner;
import sh.zolt.build.junit.PlainJunitWorkerRunner;
import sh.zolt.build.run.JavaRunner;
import sh.zolt.build.testruntime.execution.CompiledTestExecutionRunner;
import sh.zolt.build.testruntime.execution.CompiledTestRunner;
import sh.zolt.build.testruntime.execution.CurrentWorkerClasspath;
import sh.zolt.build.testruntime.execution.PlainJunitRunners;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.framework.FrameworkTestRunner;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

final class TestRuntimeFactory {
    private TestRuntimeFactory() {
    }

    static TestRuntime create(
            JdkChecker runChecker,
            FrameworkTestRunner frameworkTestRunner,
            PlainJunitWorkerPoolRunner workerPoolRunner) {
        CurrentWorkerClasspath workerClasspath = new CurrentWorkerClasspath();
        PlainJunitWorkerRunner workerRunner = PlainJunitWorkerProcessRunner::run;
        PlainJunitRunners runners = new PlainJunitRunners(
                workerClasspath::discover,
                workerRunner,
                workerPoolRunner);
        return new TestRuntime(
                invoker(
                        runChecker,
                        new JavaRunner(),
                        frameworkTestRunner,
                        runners,
                        workerEnabled(workerClasspath),
                        java.io.File.pathSeparator),
                workerPoolRunner);
    }

    static TestRuntime legacy(
            JdkChecker jdkChecker,
            JavaRunner javaRunner,
            FrameworkTestRunner frameworkTestRunner,
            Supplier<List<Path>> workerClasspath,
            PlainJunitWorkerRunner workerRunner,
            boolean workerEnabled,
            String pathSeparator) {
        return new TestRuntime(
                invoker(
                        jdkChecker,
                        javaRunner,
                        frameworkTestRunner,
                        workerClasspath,
                        workerRunner,
                        workerEnabled,
                        pathSeparator),
                new PlainJunitWorkerPoolRunner(workerRunner));
    }

    private static CompiledTestRunInvoker invoker(
            JdkChecker jdkChecker,
            JavaRunner javaRunner,
            FrameworkTestRunner frameworkTestRunner,
            PlainJunitRunners runners,
            boolean workerEnabled,
            String pathSeparator) {
        return new CompiledTestRunInvoker(
                new CompiledTestExecutionRunner(
                        new CompiledTestRunner(
                                jdkChecker,
                                javaRunner,
                                frameworkTestRunner,
                                runners,
                                workerEnabled,
                                pathSeparator)));
    }

    private static CompiledTestRunInvoker invoker(
            JdkChecker jdkChecker,
            JavaRunner javaRunner,
            FrameworkTestRunner frameworkTestRunner,
            Supplier<List<Path>> workerClasspath,
            PlainJunitWorkerRunner workerRunner,
            boolean workerEnabled,
            String pathSeparator) {
        return new CompiledTestRunInvoker(
                new CompiledTestExecutionRunner(
                        new CompiledTestRunner(
                                jdkChecker,
                                javaRunner,
                                frameworkTestRunner,
                                workerClasspath,
                                workerRunner,
                                workerEnabled,
                                pathSeparator)));
    }

    private static boolean workerEnabled(
            CurrentWorkerClasspath workerClasspath) {
        String configured = System.getProperty("zolt.junit.worker");
        return configured == null
                ? workerClasspath.dedicatedWorkerAvailable()
                : Boolean.parseBoolean(configured);
    }

    record TestRuntime(
            CompiledTestRunInvoker invoker,
            PlainJunitWorkerPoolRunner workerPoolRunner) {
    }
}
