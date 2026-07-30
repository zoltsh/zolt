package sh.zolt.build.compile;

import sh.zolt.cancel.BuildCancellation;
import sh.zolt.cancel.ProcessCancellation;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class JavacWorkerProcess implements AutoCloseable {
    private static final String MAIN_CLASS = "sh.zolt.javac.JavacWorkerMain";
    private static final int CLOSE_TIMEOUT_SECONDS = 2;

    private final Process process;
    private final DataInputStream responses;
    private final DataOutputStream requests;

    private JavacWorkerProcess(Process process) {
        this.process = process;
        this.responses = new DataInputStream(process.getInputStream());
        this.requests = new DataOutputStream(process.getOutputStream());
    }

    static JavacWorkerProcess start(Path javac, Path workerJar) throws IOException {
        Path java = javac.toAbsolutePath().normalize().resolveSibling(javaExecutableName(javac));
        Process process = new ProcessBuilder(
                java.toString(),
                "-classpath",
                workerJar.toAbsolutePath().normalize().toString(),
                MAIN_CLASS)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        return new JavacWorkerProcess(process);
    }

    JavacRunner.ProcessResult compile(int kind, List<String> arguments)
            throws IOException, InterruptedException {
        try (BuildCancellation.Registration ignored =
                ProcessCancellation.register(process)) {
            JavacWorkerWire.writeRequest(requests, kind, arguments);
            requests.flush();
            try {
                return JavacWorkerWire.readResponse(responses);
            } catch (IOException exception) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("javac worker request was cancelled");
                }
                throw exception;
            }
        }
    }

    boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        try {
            requests.close();
            if (!process.waitFor(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (IOException ignored) {
            process.destroyForcibly();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static String javaExecutableName(Path javac) {
        String fileName = javac.getFileName().toString();
        return fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".exe") ? "java.exe" : "java";
    }
}
