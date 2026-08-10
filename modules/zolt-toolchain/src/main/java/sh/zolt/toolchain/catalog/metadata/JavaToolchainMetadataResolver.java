package sh.zolt.toolchain.catalog.metadata;

import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.toolchain.platform.HostPlatform;
import java.util.List;

public interface JavaToolchainMetadataResolver {
    List<JavaToolchainRelease> resolve(JavaToolchainRequest request, List<HostPlatform> platforms);
}
