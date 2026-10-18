import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

export function checkRelease(directory, tag) {
  const read = (file) => readFileSync(resolve(directory, file), "utf8");
  const version = read("version.txt").trim();
  assert.match(version, /^\d+\.\d+\.\d+$/, "Expected a stable release version");
  const expectedTag = tag ?? `v${version}`;
  assert.equal(
    expectedTag,
    `v${version}`,
    "Release tag must match version.txt",
  );
  assert.equal(
    JSON.parse(read(".release-please-manifest.json"))["."],
    version,
    "Release Please manifest must match version.txt",
  );
  assert.equal(
    read("RELEASE_NOTES.md").split(/\r?\n/, 1)[0],
    `# ${expectedTag}`,
    "RELEASE_NOTES.md must start with the release version",
  );
}

if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(resolve(process.argv[1])).href
) {
  checkRelease(process.cwd(), process.argv[2]);
  console.log("Release version and notes are consistent.");
}
