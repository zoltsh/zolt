package sh.zolt.javac;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.ToolProvider;

/**
 * Entry point for both worker roles. With no arguments the process is a compile worker driven over
 * stdin/stdout; {@link #FRAMED_FLAG} adds a length prefix so a supervisor can relay responses without
 * parsing them; {@link #BROKER_FLAG} runs the supervisor itself.
 */
public final class JavacWorkerMain {
    static final String FRAMED_FLAG = "--framed";
    static final String BROKER_FLAG = "--broker";
    static final String WORKER_JVM_ARGUMENT_FLAG = "--worker-jvm-arg";

    private JavacWorkerMain() {
    }

    public static void main(String[] args) {
        if (args.length >= 2 && BROKER_FLAG.equals(args[0])) {
            System.exit(BrokerServer.run(Path.of(args[1]), workerJvmArguments(args), System.err));
        }
        boolean framed = args.length == 1 && FRAMED_FLAG.equals(args[0]);
        if (args.length > 0 && !framed) {
            System.err.println("error: Unknown Zolt javac worker argument: " + args[0] + ".");
            System.exit(2);
        }
        System.exit(run(System.in, System.out, System.err, framed));
    }

    static int run(InputStream input, OutputStream output, PrintStream error) {
        return run(input, output, error, false);
    }

    static int run(InputStream input, OutputStream output, PrintStream error, boolean framed) {
        if (ToolProvider.getSystemJavaCompiler() == null) {
            error.println("error: Zolt javac worker requires a JDK with the system Java compiler.");
            return 2;
        }
        try {
            DataInputStream requests = new DataInputStream(input);
            DataOutputStream responses = new DataOutputStream(output);
            while (true) {
                int kind;
                try {
                    kind = requests.readInt();
                } catch (EOFException exception) {
                    return 0;
                }
                if (kind != WorkerCompileProtocol.KIND_COMPILE
                        && kind != WorkerCompileProtocol.KIND_COMPILE_ATTRIBUTED) {
                    error.println("error: Invalid Zolt javac worker request kind: " + kind + ".");
                    return 2;
                }
                List<String> arguments = WorkerCompileProtocol.readArguments(requests);
                respond(kind, arguments, responses, framed);
            }
        } catch (IOException exception) {
            error.println("error: Zolt javac worker protocol failed: " + exception.getMessage());
            return 1;
        }
    }

    private static void respond(
            int kind,
            List<String> arguments,
            DataOutputStream responses,
            boolean framed) throws IOException {
        if (!framed) {
            WorkerCompile.run(kind, arguments, responses);
            responses.flush();
            return;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        WorkerCompile.run(kind, arguments, new DataOutputStream(buffer));
        WorkerCompileProtocol.writeFramedResponse(responses, buffer.toByteArray());
    }

    private static List<String> workerJvmArguments(String[] args) {
        List<String> arguments = new ArrayList<>();
        for (int index = 2; index < args.length - 1; index++) {
            if (WORKER_JVM_ARGUMENT_FLAG.equals(args[index])) {
                arguments.add(args[index + 1]);
            }
        }
        return arguments;
    }
}
