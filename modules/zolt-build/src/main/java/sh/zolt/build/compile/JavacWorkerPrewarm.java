package sh.zolt.build.compile;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Starts the broker and its children while the command is still deciding what to build.
 *
 * <p>A workspace command spends its first few hundred milliseconds on discovery, lock freshness and
 * dirty planning — work that needs no compiler. Spending that window launching worker JVMs means the
 * first member to be admitted finds a warm child instead of waiting for one to boot. It runs on a
 * daemon thread and reports nothing: if it fails, the build simply starts workers the usual way.
 */
public final class JavacWorkerPrewarm {
    private JavacWorkerPrewarm() {
    }

    public static void start(Path javac, int workers) {
        if (javac == null || workers < 1 || !JavacBrokerClient.enabled()) {
            return;
        }
        Optional<Path> workerJar = JavacWorkerClasspath.discover();
        if (workerJar.isEmpty()) {
            return;
        }
        Path resolvedJar = workerJar.orElseThrow();
        Thread thread = new Thread(
                () -> JavacBrokerClient.prewarm(javac, resolvedJar, workers),
                "zolt-javac-prewarm");
        thread.setDaemon(true);
        thread.start();
    }
}
