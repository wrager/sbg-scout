import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { readFile, rename, rm, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = fileURLToPath(new URL("../", import.meta.url));
const REPOSITORY = "wrager/sbg-vanilla-plus";
const ASSET_NAME = "sbg-vanilla-plus.user.js";
export const BUNDLED_SVP_PATH = `app/src/main/assets/scripts/${ASSET_NAME}`;
const LATEST_RELEASE_URL = `https://api.github.com/repos/${REPOSITORY}/releases/latest`;

async function request(fetchImpl, url, headers) {
  const response = await fetchImpl(url, {
    headers,
    signal: AbortSignal.timeout(30_000),
  });
  if (!response.ok) throw new Error(`HTTP ${response.status} for ${url}`);
  return response;
}

function validateScript(content, version) {
  const text = content.toString("utf8");
  const header = text.match(/^\/\/ ==UserScript==\r?\n([\s\S]*?)^\/\/ ==\/UserScript==\r?\n/m);
  assert.ok(header && header.index === 0, "SVP asset must start with a userscript header");
  const metadata = (name) => header[1].match(new RegExp(`^// @${name}\\s+(.+)$`, "m"))?.[1].trim();
  assert.equal(metadata("name"), "SBG Vanilla+", "Unexpected userscript name");
  assert.equal(metadata("namespace"), `https://github.com/${REPOSITORY}`, "Unexpected userscript namespace");
  assert.equal(metadata("version"), version, "SVP @version must match the release tag");
  assert.ok(text.slice(header[0].length).trim(), "SVP asset must contain code after the header");
}

/** Update only from the latest stable GitHub Release, or check without writing. */
export async function updateBundledSvp({
  root = ROOT,
  check = false,
  fetchImpl = fetch,
  token = process.env.GITHUB_TOKEN,
} = {}) {
  const headers = {
    Accept: "application/vnd.github+json",
    "User-Agent": "sbg-scout-bundled-svp",
  };
  if (token) headers.Authorization = `Bearer ${token}`;
  const release = await (await request(fetchImpl, LATEST_RELEASE_URL, headers)).json();
  assert.equal(release.draft, false, "SVP release must not be a draft");
  assert.equal(release.prerelease, false, "SVP release must be stable");
  assert.match(release.tag_name ?? "", /^v?\d+\.\d+\.\d+$/, "Expected a stable SVP release tag");
  const version = release.tag_name.replace(/^v/, "");
  const assets = release.assets?.filter((asset) => asset.name === ASSET_NAME) ?? [];
  assert.equal(assets.length, 1, `Expected exactly one ${ASSET_NAME} release asset`);
  const asset = assets[0];
  const downloadUrl = `https://github.com/${REPOSITORY}/releases/download/${release.tag_name}/${ASSET_NAME}`;
  assert.equal(asset.state, "uploaded", "SVP release asset must be uploaded");
  assert.equal(asset.browser_download_url, downloadUrl, "Expected a pinned GitHub Release asset URL");

  // Do not send the API token to the public asset URL or its redirect targets.
  const response = await request(fetchImpl, downloadUrl, { "User-Agent": headers["User-Agent"] });
  const content = Buffer.from(await response.arrayBuffer());
  assert.equal(content.length, asset.size, "SVP asset size must match GitHub Release metadata");
  const digest = `sha256:${createHash("sha256").update(content).digest("hex")}`;
  if (asset.digest != null) assert.equal(digest, asset.digest, "SVP asset SHA-256 must match GitHub Release metadata");
  validateScript(content, version);

  const assetPath = resolve(root, BUNDLED_SVP_PATH);
  let current;
  try {
    current = await readFile(assetPath);
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  const changed = !current?.equals(content);
  if (check) {
    assert.ok(!changed, `Bundled SVP differs from latest ${release.tag_name}. Run node scripts/updateBundledSvp.mjs in the release PR, then repeat verification and screenshots.`);
  } else if (changed) {
    // Validate the entire download before atomically replacing the bundled file.
    const temporaryPath = `${assetPath}.${randomUUID()}.tmp`;
    try {
      await writeFile(temporaryPath, content, { flag: "wx" });
      await rename(temporaryPath, assetPath);
    } finally {
      await rm(temporaryPath, { force: true });
    }
  }
  return { version, tag: release.tag_name, digest, downloadUrl, changed };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const argumentsList = process.argv.slice(2);
    if (argumentsList.length > 1 || argumentsList.some((argument) => argument !== "--check")) {
      throw new Error("Usage: node scripts/updateBundledSvp.mjs [--check]");
    }
    const result = await updateBundledSvp({ check: argumentsList.includes("--check") });
    console.log(`Bundled SVP ${result.tag}: ${result.changed ? "updated" : "matches latest release"} (${result.digest}).`);
    console.log(result.downloadUrl);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
