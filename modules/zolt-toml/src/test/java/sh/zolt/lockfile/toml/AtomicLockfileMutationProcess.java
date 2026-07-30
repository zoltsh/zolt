package sh.zolt.lockfile.toml;

import java.nio.file.Path;

public final class AtomicLockfileMutationProcess {
    private AtomicLockfileMutationProcess() {
    }

    public static void main(String[] arguments) throws Exception {
        Path lockfile = Path.of(arguments[0]);
        String addition = arguments[1];
        System.out.println("updating");
        System.out.flush();
        AtomicLockfileWriter.update(lockfile, current -> {
            try {
                System.out.println("entered");
                System.out.flush();
                System.in.read();
                return current + addition;
            } catch (Exception exception) {
                throw new MutationFailure(exception);
            }
        });
    }

    private static final class MutationFailure extends RuntimeException {
        private MutationFailure(Throwable cause) {
            super(cause);
        }
    }
}
