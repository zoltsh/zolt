import { expect, smoke, type SmokeContext } from "smoque";
import { join } from "node:path";

import { copyFixture, expectTestsFound, packagedZolt, runZolt, singleJar } from "./support/zolt-smoke.mts";

smoke.suite("protobuf and gRPC generation smoke", { tags: ["examples", "protobuf", "grpc"] }, async (t: SmokeContext) => {
  const root = t.repoRoot();
  const work = await t.tempDir("zolt-protobuf-grpc");
  const zolt = await packagedZolt(t);

  await t.step("generates sources, tests them, and packages generated classes", async () => {
    const project = await copyFixture(root, work, "protobuf-grpc-canary");

    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await runZolt(t, zolt, ["--no-progress", "build", "--cwd", project, "--cache-root", zolt.cacheRoot]);
    await expectTestsFound(t, zolt, 1, [
      "--no-progress",
      "test",
      "--cwd",
      project,
      "--cache-root",
      zolt.cacheRoot,
    ]);
    await runZolt(t, zolt, ["--no-progress", "package", "--cwd", project, "--cache-root", zolt.cacheRoot]);

    await expect.file(join(
      project,
      "target/generated/sources/protobuf/com/example/greeter/api/HelloRequest.java",
    )).toExist();
    await expect.file(join(
      project,
      "target/generated/sources/protobuf/com/example/greeter/api/GreeterGrpc.java",
    )).toExist();
    await expect.file(join(
      project,
      "target/generated/sources/protobuf/META-INF/zolt/protobuf/greeter.descriptor",
    )).toContain("services=Greeter");
    await expect.file(join(project, "zolt.lock")).toContain('scope = "tool-protobuf"');

    const jar = await singleJar(join(project, "target"));
    await expect.archive(jar).toContainEntries(["com/example/greeter/api/GreeterGrpc.class"]);
  });
});
