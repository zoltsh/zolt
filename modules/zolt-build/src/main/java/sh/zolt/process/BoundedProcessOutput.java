package sh.zolt.process;

final class BoundedProcessOutput {
    private final int limit;
    private final StringBuilder tail;
    private boolean hasOutput;
    private boolean endedWithNewline;

    BoundedProcessOutput(int limit) {
        this.limit = limit;
        this.tail = new StringBuilder(Math.min(limit, 8192));
    }

    synchronized void append(String chunk) {
        if (chunk.isEmpty()) {
            return;
        }
        hasOutput = true;
        endedWithNewline = chunk.charAt(chunk.length() - 1) == '\n';
        if (chunk.length() >= limit) {
            tail.setLength(0);
            tail.append(chunk, chunk.length() - limit, chunk.length());
            return;
        }
        int overflow = tail.length() + chunk.length() - limit;
        if (overflow > 0) {
            tail.delete(0, overflow);
        }
        tail.append(chunk);
    }

    synchronized String tail() {
        return tail.toString();
    }

    synchronized boolean endedWithNewline() {
        return hasOutput && endedWithNewline;
    }
}
