import { readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";

export const QUARKUS_HTTP_RESPONSES = [
  { path: "/hello", body: "Hello from Quarkus via Zolt!" },
  { path: "/config-greeting", body: "Configured greeting from Quarkus via Zolt!" },
  { path: "/static.txt", body: "Static resource from Quarkus via Zolt!\n" },
] as const;

export async function configureQuarkusHttpPort(project: string, port: number): Promise<void> {
  const properties = join(project, "src/main/resources/application.properties");
  const configured = (await readFile(properties, "utf8"))
    .replace(/quarkus\.http\.port=\d+/u, `quarkus.http.port=${port}`);
  await writeFile(properties, configured, "utf8");
}
