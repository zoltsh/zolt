package sh.zolt.quarkus.testsupport;

import sh.zolt.home.UserGlobalDirectory;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class QuarkusTestRuntimeClasspath {
    private QuarkusTestRuntimeClasspath() {
    }

    static List<URL> currentJvmUrls() {
        List<URL> urls = new ArrayList<>();
        String classpath = System.getProperty("java.class.path", "");
        if (classpath.isBlank()) {
            return urls;
        }
        for (String entry : classpath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!entry.isBlank()) {
                urls.add(url(Path.of(entry)));
            }
        }
        return urls;
    }

    public static List<Path> existingRepoCacheJars(Path repoRoot, List<String> relativeJars) {
        return existingCacheJars(candidateCacheRoots(repoRoot, System.getenv("ZOLT_CACHE_ROOT")), relativeJars);
    }

    static List<Path> candidateCacheRoots(Path repoRoot, String cacheRootOverride) {
        List<Path> cacheRoots = new ArrayList<>();
        if (cacheRootOverride != null && !cacheRootOverride.isBlank()) {
            cacheRoots.add(repoRoot.resolve(cacheRootOverride));
        }
        cacheRoots.add(repoRoot.resolve(".zolt/cache"));
        cacheRoots.add(UserGlobalDirectory.artifactCache());
        return cacheRoots;
    }

    static List<Path> existingCacheJars(List<Path> cacheRoots, List<String> relativeJars) {
        List<Path> jars = new ArrayList<>();
        for (String relativeJar : relativeJars) {
            for (Path cacheRoot : cacheRoots) {
                Path jar = cacheRoot.resolve(relativeJar);
                if (Files.isRegularFile(jar)) {
                    jars.add(jar);
                    break;
                }
            }
        }
        return jars;
    }

    public static URL url(Path path) {
        try {
            return path.toUri().toURL();
        } catch (MalformedURLException exception) {
            throw new AssertionError("Could not convert classpath entry to URL: " + path, exception);
        }
    }
}
