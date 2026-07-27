package sh.zolt.javac;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Executes one worker compile request and writes its response, shared by both transports. The legacy
 * kind keeps the exact {@code compiler.run} behavior; the attributed kind runs the Filer-recording
 * task and appends the attribution section. The response is not flushed here so callers can frame it.
 */
final class WorkerCompile {
    private static final PlainCompiler PLAIN_COMPILER = new PlainCompiler();

    private WorkerCompile() {
    }

    static void run(int kind, List<String> arguments, DataOutputStream response) throws IOException {
        if (kind == WorkerCompileProtocol.KIND_COMPILE_ATTRIBUTED) {
            AttributionCompileResult result = AttributingCompiler.compile(arguments);
            WorkerCompileProtocol.writeResponse(
                    response,
                    result.exitCode(),
                    result.diagnostics().getBytes(StandardCharsets.UTF_8),
                    result);
            return;
        }
        PlainCompiler.Result result = PLAIN_COMPILER.compile(arguments);
        WorkerCompileProtocol.writeResponse(
                response,
                result.exitCode(),
                result.diagnostics().getBytes(StandardCharsets.UTF_8),
                null);
    }
}
