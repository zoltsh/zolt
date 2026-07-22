import { chmod, readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

export interface ChannelManifest {
  readonly channel: string;
  readonly version: string;
}

export function requireManifest(manifest: ChannelManifest | undefined, label: string): ChannelManifest {
  if (manifest === undefined) {
    throw new Error(`${label} manifest was not loaded.`);
  }
  return manifest;
}

export async function readChannelManifest(path: string): Promise<ChannelManifest> {
  return parseChannelManifest(await readFile(path, "utf8"), path);
}

export async function waitForPublishedChannel(url: string, expectedVersion: string): Promise<ChannelManifest> {
  let lastVersion = "";
  for (let attempt = 0; attempt < 12; attempt++) {
    const manifest = parseChannelManifest(await readUrl(url), url);
    lastVersion = manifest.version;
    if (manifest.version === expectedVersion) {
      return manifest;
    }
    await sleep(10_000);
  }
  throw new Error(`Published channel ${url} did not reach ${expectedVersion}; last version was ${lastVersion}.`);
}

export async function downloadExecutable(url: string, output: string): Promise<void> {
  await writeFile(output, await readUrl(url), "utf8");
  await chmod(output, 0o755);
}

export async function readUrl(url: string): Promise<string> {
  if (url.startsWith("file:")) {
    return await readFile(new URL(url), "utf8");
  }
  const response = await fetch(url, { cache: "no-store" });
  if (!response.ok) {
    throw new Error(`Could not download ${url}: HTTP ${response.status}.`);
  }
  return await response.text();
}

export function requiredReleaseEnv(name: string): string {
  const value = process.env[name];
  if (value === undefined || value.trim() === "") {
    throw new Error(`${name} is required for release update smoke.`);
  }
  return value;
}

export function fileUrl(path: string): string {
  return pathToFileURL(resolve(path)).href;
}

function parseChannelManifest(text: string, label: string): ChannelManifest {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch (error) {
    throw new Error(`${label} should contain valid channel JSON: ${String(error)}`);
  }
  if (!isJsonObject(value)) {
    throw new Error(`${label} should contain a JSON object.`);
  }
  if (typeof value.channel !== "string" || value.channel.trim() === "") {
    throw new Error(`${label} channel should be a non-empty string.`);
  }
  if (typeof value.version !== "string" || value.version.trim() === "") {
    throw new Error(`${label} version should be a non-empty string.`);
  }
  return { channel: value.channel, version: value.version };
}

function isJsonObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

async function sleep(milliseconds: number): Promise<void> {
  await new Promise<void>((resolveSleep) => {
    setTimeout(resolveSleep, milliseconds);
  });
}
