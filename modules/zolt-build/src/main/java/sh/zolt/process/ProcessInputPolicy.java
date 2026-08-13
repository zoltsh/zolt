package sh.zolt.process;

/** Defines whether a supervised child can read from Zolt's standard input. */
public enum ProcessInputPolicy {
    CLOSED,
    INHERIT
}
