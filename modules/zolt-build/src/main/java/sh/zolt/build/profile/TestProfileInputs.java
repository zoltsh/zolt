package sh.zolt.build.profile;

import sh.zolt.test.runtime.TestRunException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class TestProfileInputs {
    private TestProfileInputs() {
    }

    static List<Path> workerProfiles(
            Path profileRoot,
            List<String> workerIds) {
        List<Path> profiles = new ArrayList<>();
        for (String workerId : workerIds) {
            Path worker = profileRoot
                    .resolve("workers")
                    .resolve(workerId);
            Path legacy = worker.resolve("profile.json");
            if (Files.exists(legacy)) {
                profiles.add(legacy);
            }
            profiles.addAll(requestProfiles(
                    worker.resolve("requests")));
        }
        return List.copyOf(profiles);
    }

    private static List<Path> requestProfiles(Path requests) {
        if (!Files.isDirectory(requests)) {
            return List.of();
        }
        try (var paths = Files.walk(requests, 2)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName()
                            .toString()
                            .equals("profile.json"))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new TestRunException(
                    "Could not discover JUnit worker request profiles "
                            + "under "
                            + requests
                            + ".",
                    exception);
        }
    }
}
