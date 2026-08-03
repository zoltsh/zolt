package sh.zolt.build.compile;

import sh.zolt.cancel.BuildCancellation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class JavacWorkerPool {
    private static final Map<PoolKey, WorkerPool> POOLS = new ConcurrentHashMap<>();

    private JavacWorkerPool() {
    }

    static Optional<JavacRunner.ProcessResult> compile(Path javac, int kind, List<String> arguments) {
        Optional<Path> workerJar = JavacWorkerClasspath.discover();
        if (workerJar.isEmpty()) {
            return Optional.empty();
        }
        PoolKey key = new PoolKey(
                javac.toAbsolutePath().normalize(),
                workerJar.orElseThrow().toAbsolutePath().normalize());
        if (!BuildCancellation.active()) {
            Optional<JavacRunner.ProcessResult> persistentResult = JavacWorkerDaemon.compile(
                    key.javac(),
                    key.workerJar(),
                    kind,
                    arguments);
            if (persistentResult.isPresent()) {
                return persistentResult;
            }
        }
        return POOLS.computeIfAbsent(key, WorkerPool::new).compile(kind, arguments);
    }

    private record PoolKey(Path javac, Path workerJar) {
    }

    private static final class WorkerPool {
        private final PoolKey key;
        private final int maximumSize = Math.max(1, Runtime.getRuntime().availableProcessors());
        private final ArrayDeque<JavacWorkerProcess> idle = new ArrayDeque<>();
        private int size;
        private boolean disabled;

        private WorkerPool(PoolKey key) {
            this.key = key;
        }

        private Optional<JavacRunner.ProcessResult> compile(int kind, List<String> arguments) {
            JavacWorkerProcess worker;
            try {
                worker = acquire();
            } catch (IOException | InterruptedException exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                disable();
                return Optional.empty();
            }
            if (worker == null) {
                return Optional.empty();
            }
            try {
                JavacRunner.ProcessResult result = worker.compile(kind, arguments);
                release(worker);
                return Optional.of(result);
            } catch (IOException | InterruptedException exception) {
                discard(worker);
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                disable();
                return Optional.empty();
            }
        }

        private synchronized JavacWorkerProcess acquire() throws IOException, InterruptedException {
            while (true) {
                if (disabled) {
                    return null;
                }
                JavacWorkerProcess worker = idle.pollFirst();
                if (worker != null) {
                    if (worker.isAlive()) {
                        return worker;
                    }
                    worker.close();
                    size--;
                    continue;
                }
                if (size < maximumSize) {
                    size++;
                    try {
                        return JavacWorkerProcess.start(key.javac(), key.workerJar());
                    } catch (IOException exception) {
                        size--;
                        throw exception;
                    }
                }
                wait();
            }
        }

        private synchronized void release(JavacWorkerProcess worker) {
            if (disabled || !worker.isAlive()) {
                worker.close();
                size--;
            } else {
                idle.addLast(worker);
            }
            notifyAll();
        }

        private synchronized void discard(JavacWorkerProcess worker) {
            worker.close();
            size--;
            notifyAll();
        }

        private synchronized void disable() {
            disabled = true;
            idle.forEach(JavacWorkerProcess::close);
            size -= idle.size();
            idle.clear();
            notifyAll();
        }

    }
}
