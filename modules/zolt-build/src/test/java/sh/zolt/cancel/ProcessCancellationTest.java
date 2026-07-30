package sh.zolt.cancel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class ProcessCancellationTest {
    @Test
    void terminatesRegisteredChildProcess() throws Exception {
        Process process = startSleeper();
        BuildCancellation cancellation = new BuildCancellation();

        cancellation.call(() -> {
            try (BuildCancellation.Registration ignored =
                    ProcessCancellation.register(process)) {
                assertTrue(process.isAlive());
                cancellation.cancel();
            }
            return null;
        });

        assertTrue(process.waitFor(2, TimeUnit.SECONDS));
        assertFalse(process.isAlive());
    }

    private static Process startSleeper() throws IOException {
        Path java = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "")
                                .toLowerCase()
                                .contains("win")
                        ? "java.exe"
                        : "java");
        return new ProcessBuilder(
                java.toString(),
                "-classpath",
                System.getProperty("java.class.path"),
                Sleeper.class.getName())
                .start();
    }

    public static final class Sleeper {
        private Sleeper() {
        }

        public static void main(String[] arguments) throws InterruptedException {
            new java.util.concurrent.CountDownLatch(1).await();
        }
    }
}
