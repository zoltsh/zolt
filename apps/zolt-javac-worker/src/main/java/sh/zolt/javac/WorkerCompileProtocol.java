package sh.zolt.javac;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire framing shared by both worker transports (the persistent socket server and the stdin/stdout
 * subprocess). Version 2 adds a request-kind discriminator ahead of the argument vector and an
 * attribution section after the diagnostics in the response. Kind {@link #KIND_COMPILE} is the legacy
 * path (byte-identical behavior); kind {@link #KIND_COMPILE_ATTRIBUTED} runs the Filer-recording task.
 */
final class WorkerCompileProtocol {
    static final int MAGIC = 0x5a4f4c54;
    static final int PROTOCOL_VERSION = 2;
    static final int KIND_COMPILE = 0;
    static final int KIND_COMPILE_ATTRIBUTED = 1;

    private static final int MAX_ARGUMENTS = 100_000;
    private static final int MAX_STRING_BYTES = 64 * 1024 * 1024;
    private static final int MAX_HANDSHAKE_BYTES = 4096;

    private WorkerCompileProtocol() {
    }

    static int readKind(DataInputStream input) throws IOException {
        int kind = input.readInt();
        if (kind != KIND_COMPILE && kind != KIND_COMPILE_ATTRIBUTED) {
            throw new IOException("invalid request kind " + kind);
        }
        return kind;
    }

    static List<String> readArguments(DataInputStream input) throws IOException {
        int argumentCount = input.readInt();
        if (argumentCount < 0 || argumentCount > MAX_ARGUMENTS) {
            throw new IOException("invalid argument count " + argumentCount);
        }
        List<String> arguments = new ArrayList<>(argumentCount);
        for (int index = 0; index < argumentCount; index++) {
            arguments.add(readString(input));
        }
        return arguments;
    }

    /** A handshake field, bounded so an unauthenticated peer cannot ask the broker for memory. */
    static String readHandshakeString(DataInputStream input) throws IOException {
        return readString(input, MAX_HANDSHAKE_BYTES);
    }

    static String readString(DataInputStream input) throws IOException {
        return readString(input, MAX_STRING_BYTES);
    }

    private static String readString(DataInputStream input, int maximumBytes) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximumBytes) {
            throw new IOException("invalid string length " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("incomplete string payload");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static void writeResponse(
            DataOutputStream output,
            int exitCode,
            byte[] diagnostics,
            AttributionCompileResult attribution) throws IOException {
        output.writeInt(exitCode);
        writeBytes(output, diagnostics);
        if (attribution == null || !attribution.present()) {
            output.writeInt(0);
            return;
        }
        output.writeInt(1);
        output.writeInt(attribution.unattributed() ? 1 : 0);
        output.writeInt(attribution.entries().size());
        for (GeneratedFileRecord entry : attribution.entries()) {
            writeString(output, entry.path());
            output.writeInt(entry.kind());
            writeString(output, entry.createdType());
            output.writeInt(entry.originatingTypes().size());
            for (String originatingType : entry.originatingTypes()) {
                writeString(output, originatingType);
            }
        }
    }

    /** Length-prefixes a response so a supervisor can relay it without understanding its shape. */
    static void writeFramedResponse(DataOutputStream output, byte[] response) throws IOException {
        output.writeInt(response.length);
        output.write(response);
        output.flush();
    }

    static byte[] readFramedResponse(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("invalid framed response length " + length);
        }
        byte[] response = input.readNBytes(length);
        if (response.length != length) {
            throw new EOFException("incomplete framed response");
        }
        return response;
    }

    static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void writeBytes(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
