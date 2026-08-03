package sh.zolt.cli.command;

import sh.zolt.build.junit.PlainJunitWorkerPoolRunner;
import sh.zolt.build.junit.PlainJunitWorkerProcessSessionFactory;
import sh.zolt.build.testruntime.TestRunService;
import sh.zolt.cli.command.CommandServiceClusters.CommandTestFrameworkServices;
import sh.zolt.doctor.JdkChecker;

final class CommandWorkspaceTestRunServices {
    private CommandWorkspaceTestRunServices() {
    }

    static CommandServiceBundles.TestRunServiceFactory persistentFactory(
            CommandTestFrameworkServices testFrameworkServices) {
        ThreadLocal<PlainJunitWorkerPoolRunner> workerPools =
                ThreadLocal.withInitial(() ->
                        PlainJunitWorkerPoolRunner.persistent(
                                new PlainJunitWorkerProcessSessionFactory()));
        return (compileChecker, runChecker) -> new TestRunService(
                compileChecker,
                runChecker,
                testFrameworkServices.frameworkTestRunner(),
                testFrameworkServices.resolveService(),
                workerPools.get());
    }

    static TestRunService once(
            CommandTestFrameworkServices testFrameworkServices,
            JdkChecker compileChecker,
            JdkChecker runChecker) {
        return new TestRunService(
                compileChecker,
                runChecker,
                testFrameworkServices.frameworkTestRunner(),
                testFrameworkServices.resolveService(),
                new PlainJunitWorkerPoolRunner(
                        new PlainJunitWorkerProcessSessionFactory()));
    }
}
