package sh.zolt.javac;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import javax.annotation.processing.Processor;

/** Loads processors in an isolated loader owned by one compilation, including lazy resource reads. */
final class ProcessorLoader {
    private ProcessorLoader() {
    }

    static Loaded load(List<Path> processorPath) {
        if (processorPath.isEmpty()) {
            return new Loaded(null, List.of());
        }
        URLClassLoader loader = newLoader(processorPath);
        try {
            List<Processor> processors = new ArrayList<>();
            for (Processor processor : ServiceLoader.load(Processor.class, loader)) {
                processors.add(processor);
            }
            return new Loaded(loader, List.copyOf(processors));
        } catch (RuntimeException | Error failure) {
            try {
                loader.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    record Loaded(URLClassLoader loader, List<Processor> processors) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (loader != null) {
                loader.close();
            }
        }
    }

    private static URLClassLoader newLoader(List<Path> processorPath) {
        List<URL> urls = new ArrayList<>();
        for (Path entry : processorPath) {
            try {
                urls.add(entry.toAbsolutePath().normalize().toUri().toURL());
            } catch (MalformedURLException exception) {
                throw new IllegalStateException("Invalid processor path entry: " + entry, exception);
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), ProcessorLoader.class.getClassLoader());
    }
}
