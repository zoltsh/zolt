package sh.zolt.build.compile;

/**
 * Client half of the broker transport, mirroring {@code sh.zolt.javac.BrokerProtocol} in the worker
 * artifact. The two are kept in step by {@link #VERSION}: a broker that speaks anything else declines
 * the handshake and the command falls back to its own workers rather than guessing at the framing.
 */
final class JavacBrokerWire {
    static final int VERSION = 3;

    static final int HELLO_DECLINE = 0;
    static final int HELLO_ACCEPT = 1;

    static final int FRAME_REQUEST = 0;
    static final int FRAME_CANCEL = 1;
    static final int FRAME_GOODBYE = 2;
    static final int FRAME_PREWARM = 3;

    static final int STATUS_OK = 0;
    static final int STATUS_CANCELLED = 1;
    static final int STATUS_FAILED = 2;

    private JavacBrokerWire() {
    }
}
