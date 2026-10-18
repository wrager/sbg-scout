import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtemp, mkdir, readFile, readdir, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import test from "node:test";
import { BUNDLED_SVP_PATH, updateBundledSvp } from "./updateBundledSvp.mjs";

const LATEST_URL = "https://api.github.com/repos/wrager/sbg-vanilla-plus/releases/latest";
const DOWNLOAD_URL = "https://github.com/wrager/sbg-vanilla-plus/releases/download/v1.2.3/sbg-vanilla-plus.user.js";
const SCRIPT = `// ==UserScript==
// @name         SBG Vanilla+
// @namespace    https://github.com/wrager/sbg-vanilla-plus
// @version      1.2.3
// ==/UserScript==

(function () { "use strict"; })();
`;
const OLD_SCRIPT = SCRIPT.replace("1.2.3", "1.2.2");

async function fixture(t, content = SCRIPT) {
  const root = await mkdtemp(join(tmpdir(), "bundled-svp-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const assetPath = join(root, BUNDLED_SVP_PATH);
  await mkdir(dirname(assetPath), { recursive: true });
  await writeFile(assetPath, OLD_SCRIPT);
  const release = {
    tag_name: "v1.2.3",
    draft: false,
    prerelease: false,
    assets: [{
      name: "sbg-vanilla-plus.user.js",
      state: "uploaded",
      browser_download_url: DOWNLOAD_URL,
      size: Buffer.byteLength(content),
      digest: `sha256:${createHash("sha256").update(content).digest("hex")}`,
    }],
  };
  const requests = [];
  const options = {
    root,
    token: "test-api-token",
    fetchImpl: async (url, request) => {
      requests.push({ url, request });
      if (url === LATEST_URL) return Response.json(release);
      assert.equal(url, DOWNLOAD_URL);
      return new Response(content);
    },
  };
  return { root, assetPath, release, options, requests };
}

test("update uses the pinned stable release asset and preserves its exact bytes", async (t) => {
  const { assetPath, options, requests } = await fixture(t, SCRIPT.replaceAll("\n", "\r\n"));
  const result = await updateBundledSvp(options);
  assert.equal(result.changed, true);
  assert.equal(result.version, "1.2.3");
  assert.equal(result.tag, "v1.2.3");
  assert.equal(result.downloadUrl, DOWNLOAD_URL);
  assert.equal(await readFile(assetPath, "utf8"), SCRIPT.replaceAll("\n", "\r\n"));
  assert.deepEqual(await readdir(dirname(assetPath)), ["sbg-vanilla-plus.user.js"]);
  assert.deepEqual(requests.map(({ url }) => url), [LATEST_URL, DOWNLOAD_URL]);
  assert.equal(requests[0].request.headers.Authorization, "Bearer test-api-token");
  assert.equal(requests[1].request.headers.Authorization, undefined);
  assert.ok(requests.every(({ request }) => request.signal instanceof AbortSignal));
});

test("update is idempotent and check never rewrites a matching asset", async (t) => {
  const { assetPath, options } = await fixture(t);
  await updateBundledSvp(options);
  const before = await stat(assetPath);
  assert.equal((await updateBundledSvp(options)).changed, false);
  assert.equal((await updateBundledSvp({ ...options, check: true })).changed, false);
  assert.equal((await stat(assetPath)).mtimeMs, before.mtimeMs);
});

test("check rejects stale content without modifying it", async (t) => {
  const { assetPath, options } = await fixture(t);
  await assert.rejects(updateBundledSvp({ ...options, check: true }), /differs from latest v1.2.3/);
  assert.equal(await readFile(assetPath, "utf8"), OLD_SCRIPT);
});

test("check rejects changed code even when @version matches", async (t) => {
  const { assetPath, options } = await fixture(t);
  await writeFile(assetPath, SCRIPT.replace("use strict", "changed code"));
  await assert.rejects(updateBundledSvp({ ...options, check: true }), /differs from latest/);
});

test("a missing asset fails check and can be restored by update", async (t) => {
  const { assetPath, options } = await fixture(t);
  await rm(assetPath);
  await assert.rejects(updateBundledSvp({ ...options, check: true }), /differs from latest/);
  assert.equal((await updateBundledSvp(options)).changed, true);
  assert.equal(await readFile(assetPath, "utf8"), SCRIPT);
});

test("public releases work without a token or GitHub digest", async (t) => {
  const { release, options, requests } = await fixture(t);
  release.assets[0].digest = null;
  await updateBundledSvp({ ...options, token: "" });
  assert.equal(requests[0].request.headers.Authorization, undefined);
});

const invalidReleases = [
  ["draft", (release) => { release.draft = true; }, /must not be a draft/],
  ["prerelease", (release) => { release.prerelease = true; }, /must be stable/],
  ["unstable tag", (release) => { release.tag_name = "v1.2.3-beta.1"; }, /stable SVP release tag/],
  ["missing tag", (release) => { delete release.tag_name; }, /stable SVP release tag/],
  ["missing asset", (release) => { release.assets = []; }, /exactly one/],
  ["only meta asset", (release) => { release.assets[0].name = "sbg-vanilla-plus.meta.js"; }, /exactly one/],
  ["duplicate asset", (release) => { release.assets.push({ ...release.assets[0] }); }, /exactly one/],
  ["incomplete upload", (release) => { release.assets[0].state = "starter"; }, /must be uploaded/],
  ["unpinned URL", (release) => { release.assets[0].browser_download_url = DOWNLOAD_URL.replace("download/v1.2.3", "latest/download"); }, /pinned GitHub Release/],
  ["source URL", (release) => { release.assets[0].browser_download_url = "https://raw.githubusercontent.com/wrager/sbg-vanilla-plus/main/script.js"; }, /pinned GitHub Release/],
  ["truncated download", (release) => { release.assets[0].size += 1; }, /asset size/],
  ["digest mismatch", (release) => { release.assets[0].digest = `sha256:${"0".repeat(64)}`; }, /SHA-256/],
];
for (const [name, change, error] of invalidReleases) {
  test(`reject ${name} without replacing the bundled asset`, async (t) => {
    const { assetPath, release, options } = await fixture(t);
    change(release);
    await assert.rejects(updateBundledSvp(options), error);
    assert.equal(await readFile(assetPath, "utf8"), OLD_SCRIPT);
    assert.deepEqual(await readdir(dirname(assetPath)), ["sbg-vanilla-plus.user.js"]);
  });
}

const invalidScripts = [
  ["HTML response", "<html>Not a script</html>", /userscript header/],
  ["version mismatch", SCRIPT.replace("1.2.3", "1.2.4"), /@version/],
  ["wrong name", SCRIPT.replace("SBG Vanilla+", "Other script"), /userscript name/],
  ["wrong namespace", SCRIPT.replace("wrager/sbg-vanilla-plus", "other/project"), /namespace/],
  ["missing code", SCRIPT.slice(0, SCRIPT.indexOf("(function")), /contain code/],
];
for (const [name, content, error] of invalidScripts) {
  test(`reject ${name} even with a valid size and digest`, async (t) => {
    const { assetPath, options } = await fixture(t, content);
    await assert.rejects(updateBundledSvp(options), error);
    assert.equal(await readFile(assetPath, "utf8"), OLD_SCRIPT);
  });
}

for (const failingUrl of [LATEST_URL, DOWNLOAD_URL]) {
  for (const status of [403, 404, 500]) {
    test(`HTTP ${status} at ${failingUrl} fails without falling back to stale SVP`, async (t) => {
      const { assetPath, options } = await fixture(t);
      const fetchImpl = options.fetchImpl;
      options.fetchImpl = (url, request) => url === failingUrl
        ? new Response("Unavailable", { status }) : fetchImpl(url, request);
      await assert.rejects(updateBundledSvp(options), new RegExp(`HTTP ${status}`));
      assert.equal(await readFile(assetPath, "utf8"), OLD_SCRIPT);
    });
  }
}

test("network failure leaves the bundled asset intact", async (t) => {
  const { assetPath, options } = await fixture(t);
  options.fetchImpl = async () => { throw new Error("Network unavailable"); };
  await assert.rejects(updateBundledSvp(options), /Network unavailable/);
  assert.equal(await readFile(assetPath, "utf8"), OLD_SCRIPT);
});


test("Git preserves upstream CRLF bytes with automatic line-ending conversion enabled", async (t) => {
  const { root, assetPath } = await fixture(t);
  const content = SCRIPT.replaceAll("\n", "\r\n");
  await writeFile(assetPath, content);
  await writeFile(join(root, ".gitattributes"), await readFile(new URL("../.gitattributes", import.meta.url)));
  const git = (...argumentsList) => {
    const result = spawnSync("git", argumentsList, { cwd: root, encoding: "utf8" });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.trim();
  };
  git("init", "--quiet");
  const filtered = git("-c", "core.autocrlf=true", "hash-object", "--path=" + BUNDLED_SVP_PATH, assetPath);
  assert.equal(filtered, git("hash-object", "--no-filters", assetPath));
});
