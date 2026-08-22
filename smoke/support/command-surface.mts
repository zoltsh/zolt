// The public command surface listed by `zolt --list`. Zolt's own release and self-hosting machinery
// (self-check, self-parity, native-smoke, release-archive, release-index, release-verify) is hidden
// from the listing and is therefore absent here, but stays invokable.
export const EXPECTED_ZOLT_COMMANDS = [
  "help", "init", "version", "config", "doctor", "self",
  "add", "remove", "versions", "platforms", "bom", "resolve", "tree", "why", "policy", "conflicts",
  "outdated", "update",
  "aliases", "tasks", "task", "build", "run", "exec", "test", "integration-test", "coverage",
  "package", "run-package", "clean",
  "check", "plan", "classpath", "ide", "toolchain", "shims", "explain", "quarkus",
  "native", "publish",
  "sbom", "licenses", "workspace", "cache",
] as const;

export function parseListedCommands(output: string): string[] {
  const commands = output
    .split(/\r?\n/u)
    .filter((line) => /^ {4}[a-z]/u.test(line))
    .map((line) => line.trim().split(/\s+/u)[0]);
  if (commands.length === 0) {
    throw new Error("Could not parse any commands from `zolt --list` output.");
  }
  return commands;
}
