import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync, unlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { checkRelease } from "./checkRelease.mjs";

function fixture(t) {
  const directory = mkdtempSync(join(tmpdir(), "release-check-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const write = (file, value) => writeFileSync(join(directory, file), value);
  write(".release-please-manifest.json", JSON.stringify({ ".": "1.2.0" }));
  write("RELEASE_NOTES.md", "# v1.2.0\n\nИзменения для игроков.\n");
  write("version.txt", "1.2.0\n");
  return { directory, write };
}

test("matching notes and version pass, including CRLF notes", (t) => {
  const { directory, write } = fixture(t);
  checkRelease(directory);
  write("RELEASE_NOTES.md", "# v1.2.0\r\n\r\nИзменения.\r\n");
  checkRelease(directory, "v1.2.0");
});

for (const heading of ["# v1.1.9", "# Изменения", "", "# v1.3.0"]) {
  test(`reject notes with a stale, missing or mismatched heading: ${heading}`, (t) => {
    const { directory, write } = fixture(t);
    write("RELEASE_NOTES.md", `${heading}\n\nИзменения.\n`);
    assert.throws(() => checkRelease(directory), /RELEASE_NOTES.md/);
  });
}

test("reject a missing notes file", (t) => {
  const { directory } = fixture(t);
  unlinkSync(join(directory, "RELEASE_NOTES.md"));
  assert.throws(() => checkRelease(directory), /ENOENT/);
});

test("reject a different release tag and inconsistent manifest", (t) => {
  const { directory, write } = fixture(t);
  assert.throws(() => checkRelease(directory, "v1.3.0"), /Release tag/);
  write(".release-please-manifest.json", JSON.stringify({ ".": "1.1.9" }));
  assert.throws(() => checkRelease(directory), /manifest/);
});
