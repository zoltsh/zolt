package sh.zolt.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.resolve.progress.ArtifactProgressListener;
import sh.zolt.resolve.support.ResolveServiceTestSupport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

final class ResolveServiceTest extends ResolveServiceTestSupport {
    @Test
    void resolveDownloadsArtifactsAndWritesLockfile() {
        Path projectDir = tempDir.resolve("project");
        Path cacheRoot = tempDir.resolve("cache");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(projectDir, config(), cacheRoot);

        assertEquals(2, result.resolvedCount());
        assertEquals(4, result.downloadCount());
        assertEquals(0, result.conflictCount());
        assertEquals(projectDir.resolve("zolt.lock"), result.lockfilePath());
        assertTrue(result.metrics().pomDownloadNanos() > 0);
        assertTrue(result.metrics().artifactDownloadNanos() > 0);
        // A POM asked for twice — once to traverse it, once to lock it — is downloaded once and then
        // served from the session, so the repeat is a hit whose cost is a map lookup rather than a
        // re-read of the cache file.
        assertTrue(result.metrics().pomCacheHits() > 0);
        assertEquals(0, result.metrics().artifactCacheHitNanos());
        assertTrue(result.metrics().rawPomParseNanos() > 0);
        assertTrue(result.metrics().effectivePomBuildNanos() > 0);
        assertTrue(result.metrics().graphTraversalNanos() > 0);
        assertTrue(result.metrics().lockfileAssemblyNanos() > 0);
        assertTrue(result.metrics().lockfileWriteNanos() > 0);
        assertEquals(0, result.metrics().lockfileVerificationNanos());
        assertTrue(Files.exists(result.lockfilePath()));

        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.projectResolutionFingerprint().orElseThrow().startsWith("sha256:"));
        assertEquals(2, lockfile.packages().size());
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "app")) && lockPackage.direct()));
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "lib")) && !lockPackage.direct()));
    }

    @Test
    void artifactProgressListenerDoesNotChangeResolvedLockfile() throws IOException {
        Path noListenerProject = tempDir.resolve("project-no-listener");
        Path listenerProject = tempDir.resolve("project-listener");
        createDirectory(noListenerProject);
        createDirectory(listenerProject);
        RecordingArtifactProgressListener listener = new RecordingArtifactProgressListener();

        ResolveResult noListener = resolveService.resolve(
                noListenerProject,
                config(),
                tempDir.resolve("cache-no-listener"),
                false,
                ResolveOptions.defaults());
        ResolveResult withListener = resolveService.resolve(
                listenerProject,
                config(),
                tempDir.resolve("cache-listener"),
                false,
                ResolveOptions.defaults().withArtifactProgressListener(listener));

        assertEquals(noListener.resolvedCount(), withListener.resolvedCount());
        assertEquals(noListener.downloadCount(), withListener.downloadCount());
        assertEquals(
                Files.readString(noListener.lockfilePath()),
                Files.readString(withListener.lockfilePath()));
        assertTrue(listener.events().contains("start com.example:app:1.0.0::pom"));
        assertTrue(listener.events().contains("complete com.example:app:1.0.0::jar"));
        assertTrue(listener.byteEvents().stream().allMatch(event ->
                event.received() > 0L
                        && event.total() > 0L
                        && event.received() <= event.total()));
        assertTrue(listener.byteEvents().stream().anyMatch(event ->
                event.key().equals("com.example:app:1.0.0::jar")
                        && event.received() == event.total()));
    }

    private static final class RecordingArtifactProgressListener implements ArtifactProgressListener {
        private final CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<ByteEvent> byteEvents = new CopyOnWriteArrayList<>();

        @Override
        public void onStart(ArtifactDescriptor descriptor) {
            events.add("start " + key(descriptor));
        }

        @Override
        public void onComplete(ArtifactDescriptor descriptor, long bytes) {
            events.add("complete " + key(descriptor));
        }

        @Override
        public void onBytes(ArtifactDescriptor descriptor, long received, long total) {
            byteEvents.add(new ByteEvent(key(descriptor), received, total));
        }

        private List<String> events() {
            return List.copyOf(events);
        }

        private List<ByteEvent> byteEvents() {
            return List.copyOf(byteEvents);
        }

        private static String key(ArtifactDescriptor descriptor) {
            return descriptor.coordinate()
                    + ":"
                    + descriptor.classifier().orElse("")
                    + ":"
                    + descriptor.extension();
        }
    }

    private record ByteEvent(String key, long received, long total) {
    }
}
