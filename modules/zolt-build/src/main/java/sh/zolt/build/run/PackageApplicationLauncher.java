package sh.zolt.build.run;

import sh.zolt.build.RunPackageException;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.classpath.Classpath;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies {@link PackageLaunchPolicy} and invokes Java without letting workspace execution diverge.
 */
public final class PackageApplicationLauncher {
    private final JavaRunner javaRunner;

    public PackageApplicationLauncher(JavaRunner javaRunner) {
        this.javaRunner = javaRunner;
    }

    public JavaRunResult launch(
            Path java,
            PackageResult packageResult,
            List<Path> runtimeEntries,
            String mainClass,
            List<String> arguments) {
        PackageLaunchPolicy.Decision decision =
                PackageLaunchPolicy.forMode(packageResult.mode());
        if (decision.strategy() == PackageLaunchPolicy.Strategy.REJECT) {
            throw new RunPackageException(decision.rejection());
        }
        if (decision.strategy() == PackageLaunchPolicy.Strategy.JAR) {
            return javaRunner.runJar(
                    java,
                    packageResult.jarPath(),
                    mainClass,
                    arguments);
        }
        List<Path> classpath = new ArrayList<>();
        classpath.add(packageResult.jarPath());
        classpath.addAll(runtimeEntries);
        return javaRunner.run(
                java,
                new Classpath(classpath),
                mainClass,
                arguments);
    }
}
