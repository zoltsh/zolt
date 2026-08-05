package sh.zolt.javac;

/**
 * Framing for the broker transport: one multiplexed connection per CLI command, carrying many
 * concurrent compile requests to a supervisor that owns a pool of child worker JVMs.
 *
 * <p>The broker never compiles in its own process. Every request is executed by a child JVM leased
 * for the duration of that request, so a cancelled or disconnected session can be stopped by killing
 * the child rather than by asking a shared server thread to stop mutating build outputs.
 *
 * <p>Handshake: {@code MAGIC}, {@code VERSION}, token, session identifier. The broker answers
 * {@link #HELLO_ACCEPT} or {@link #HELLO_DECLINE}; a declined client falls back to command-local
 * workers. Client frames are then {@link #FRAME_REQUEST}, {@link #FRAME_CANCEL},
 * {@link #FRAME_PREWARM} and {@link #FRAME_GOODBYE}; the broker answers requests with response
 * frames tagged by the request identifier, so responses may arrive in any order.
 */
final class BrokerProtocol {
    static final int MAGIC = 0x5a4f4c54;

    /** Broker transport version, independent of the {@link WorkerCompileProtocol} child framing. */
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

    static final int MAX_PREWARM = 512;

    private BrokerProtocol() {
    }
}
