import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

function version(tag) {
  const match = /^v?(\d+)\.(\d+)(?:\.(\d+))?$/.exec(tag);
  return match ? match.slice(1).map((part) => Number(part ?? 0)) : null;
}

function compareVersions(left, right) {
  for (let index = 0; index < 3; index++) {
    if (left[index] !== right[index]) return left[index] - right[index];
  }
  return 0;
}

export function previousRelease(releases, tag) {
  const current = version(tag);
  assert.ok(current, "Expected a stable release tag");
  const candidates = releases.filter((release) => {
    const candidate = version(release.tag_name);
    return release.draft === false && release.prerelease === false && candidate &&
      compareVersions(candidate, current) < 0;
  });
  candidates.sort((left, right) => compareVersions(version(right.tag_name), version(left.tag_name)));
  assert.ok(candidates.length, "No previous published stable release; refusing to skip the signing check");
  return candidates[0];
}

export function certificateFingerprints(output) {
  const fingerprints = [...output.matchAll(/^Signer #\d+ certificate SHA-256 digest: ([a-f\d]{64})\r?$/gim)]
    .map((match) => match[1].toLowerCase()).sort();
  assert.ok(fingerprints.length, "apksigner did not report a signing certificate");
  return fingerprints;
}

/** Fail closed if either APK is invalid or the previous release cannot be checked. */
export function checkReleaseSigning({
  tag,
  apk,
  repository = process.env.GITHUB_REPOSITORY ?? "wrager/sbg-scout",
  apksignerJar = join(process.env.ANDROID_HOME ?? "", "build-tools/36.0.0/lib/apksigner.jar"),
  run = (command, args) => execFileSync(command, args, {
    encoding: "utf8", timeout: 60_000, maxBuffer: 16 * 1024 * 1024,
  }),
  temporaryRoot = tmpdir(),
}) {
  const verify = (path) => certificateFingerprints(run("java", [
    "-jar", apksignerJar, "verify", "--print-certs", path,
  ]));
  const current = verify(resolve(apk));
  const pages = JSON.parse(run("gh", [
    "api", "--paginate", "--slurp", `repos/${repository}/releases?per_page=100`,
  ]));
  const previous = previousRelease(pages.flat(), tag);
  const assets = previous.assets.filter((asset) => asset.name.endsWith(".apk"));
  assert.equal(assets.length, 1, `Expected exactly one APK in ${previous.tag_name}`);
  const asset = assets[0];
  assert.equal(asset.state, "uploaded", "Previous release APK must be uploaded");
  assert.match(asset.name, /^[a-z\d][a-z\d._-]*\.apk$/i, "Unexpected APK asset name");
  const directory = mkdtempSync(join(temporaryRoot, "release-signing-"));
  try {
    run("gh", ["release", "download", previous.tag_name, "--repo", repository,
      "--pattern", asset.name, "--dir", directory]);
    const expected = verify(join(directory, asset.name));
    assert.deepEqual(current, expected,
      `Signing certificate differs from ${previous.tag_name}; existing users cannot update`);
    return { previousTag: previous.tag_name, fingerprints: current };
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    assert.equal(process.argv.length, 4, "Usage: node scripts/checkReleaseSigning.mjs <tag> <apk>");
    const result = checkReleaseSigning({ tag: process.argv[2], apk: process.argv[3] });
    console.log(`APK signing certificate matches ${result.previousTag}: ${result.fingerprints.join(", ")}`);
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
