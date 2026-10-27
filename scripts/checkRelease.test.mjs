import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readFileSync, existsSync, rmSync, writeFileSync, unlinkSync } from "node:fs";
import { execFileSync, spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import test from "node:test";
import { runInNewContext } from "node:vm";
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

function verifyReleaseAssets(t, environment = {}) {
  const { directory, write } = fixture(t);
  const workflow = readFileSync(new URL('../.github/workflows/release.yml', import.meta.url), 'utf8').replace(/\r\n/g, '\n');
  const commands = workflow.match(/      - name: Verify release assets\n        run: \|\n((?:          .*\n)+)/)?.[1];
  assert.ok(commands, 'Missing release asset verification commands');
  const executable = (path, source) => {
    const target = join(directory, path);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, `#!/bin/bash\n${source}\n`, { mode: 0o755 });
  };
  executable('sdk tools/build-tools/36.0.0/apksigner', 'exit "${SIGNATURE_STATUS:-0}"');
  executable('sdk tools/build-tools/36.0.0/aapt', `test "$1" = dump && test "$2" = badging || exit 1
printf '%s\\n' "package: name='com.github.wrager.sbgscout' versionCode='\${APK_VERSION_CODE:-10300}' versionName='\${APK_VERSION_NAME:-1.3.0}' platformBuildVersionName='16'"
touch aapt-called`);
  executable('bin/unzip', 'printf %s "${APK_BUNDLE:-bundled script}"');
  mkdirSync(join(directory, 'app/build/outputs/apk/release'), { recursive: true });
  mkdirSync(join(directory, 'app/src/main/assets/scripts'), { recursive: true });
  write('app/build/outputs/apk/release/app-release.apk', 'signed apk fixture');
  write('app/src/main/assets/scripts/sbg-vanilla-plus.user.js', 'bundled script');
  const gitPath = execFileSync('git', ['--exec-path'], { encoding: 'utf8' }).trim();
  const shell = process.platform === 'win32' ? resolve(gitPath, '../../../bin/bash.exe') : 'bash';
  const script = `export PATH="$PWD/bin:/usr/bin:/bin"\nexport ANDROID_HOME="$PWD/sdk tools"\n${commands.replace(/^          /gm, '')}`;
  const result = spawnSync(shell, ['--noprofile', '--norc', '-e', '-o', 'pipefail', '-c', script], {
    cwd: directory,
    encoding: 'utf8',
    env: { ...process.env, RELEASE_TAG: 'v1.3.0', ...environment },
  });
  assert.ifError(result.error);
  return {
    ...result,
    directory,
    renamed: existsSync(join(directory, 'app/build/outputs/apk/release/sbg-scout-v1.3.0.apk')),
  };
}

test('release workflow verifies APK without apkanalyzer on PATH', (t) => {
  const result = verifyReleaseAssets(t);
  assert.equal(result.status, 0, result.stderr);
  assert.ok(result.renamed);
  assert.ok(existsSync(join(result.directory, 'aapt-called')));
});

for (const [condition, environment] of [
  ['wrong version name', { APK_VERSION_NAME: '1.2.0' }],
  ['wrong version code', { APK_VERSION_CODE: '10200' }],
  ['invalid signature', { SIGNATURE_STATUS: '1' }],
  ['different bundled script', { APK_BUNDLE: 'different script' }],
]) {
  test(`release workflow rejects ${condition}`, (t) => {
    const result = verifyReleaseAssets(t, environment);
    assert.equal(result.status, 1, result.stderr);
    assert.doesNotMatch(result.stderr, /command not found/);
    assert.equal(result.renamed, false);
  });
}

test('release workflow routes automatic and manual publication to the selected commit', () => {
  const workflow = readFileSync(new URL('../.github/workflows/release.yml', import.meta.url), 'utf8').replace(/\r\n/g, '\n');
  const publish = workflow.split('\n  publish:\n')[1];
  assert.ok(publish);
  const evaluate = (expression, context) => {
    assert.ok(expression, 'Missing workflow expression');
    const source = expression.trim().replace(/^\$\{\{\s*|\s*\}\}$/g, '').replace(/needs\.([\w-]+)/g, 'needs["$1"]');
    return runInNewContext(source, context);
  };
  const automatic = workflow.match(/  release-please:\n    if: (.+)/)?.[1];
  const recovery = workflow.match(/  recover:\n    if: (.+)/)?.[1];
  const publishCondition = publish.match(/    if: >-\n([\s\S]*?)    runs-on:/)?.[1];
  const selectedSha = publish.match(/      RELEASE_SHA: (.+)/)?.[1];
  const selectedTag = publish.match(/      RELEASE_TAG: (.+)/)?.[1];
  const checkout = publish.match(/          ref: (.+)/)?.[1];
  const publishAction = publish.split('      - name: Publish GitHub Release after uploading APK\n')[1];
  assert.ok(publishAction);
  const automaticPublication = publishAction.match(/        if: (.+)/)?.[1] || 'true';
  const artifact = publish.match(/      - name: Save verified recovery APK\n([\s\S]*?)(?=\n      - name:)/)?.[1];
  for (const [event, ref, created, recovered, expected] of [
    ['push', 'refs/heads/main', 'true', false, 'a'.repeat(40)],
    ['push', 'refs/heads/main', 'false', false, undefined],
    ['workflow_dispatch', 'refs/heads/main', '', true, 'b'.repeat(40)],
    ['workflow_dispatch', 'refs/heads/main', '', false, undefined],
    ['workflow_dispatch', 'refs/heads/other', '', true, undefined],
  ]) {
    const context = { github: { event_name: event, ref }, cancelled: () => false };
    const automaticRuns = evaluate(automatic, context);
    const recoveryRuns = evaluate(recovery, context);
    context.needs = {
      'release-please': { result: automaticRuns ? 'success' : 'skipped', outputs: automaticRuns ? { release_created: created, sha: 'a'.repeat(40), tag_name: 'v1.4.0' } : {} },
      recover: { result: recoveryRuns ? (recovered ? 'success' : 'failure') : 'skipped', outputs: recoveryRuns && recovered ? { sha: 'b'.repeat(40), tag_name: 'v1.3.0' } : {} },
    };
    assert.equal(Boolean(evaluate(publishCondition, context)), expected !== undefined);
    if (expected) {
      context.env = { RELEASE_SHA: evaluate(selectedSha, context), RELEASE_TAG: evaluate(selectedTag, context) };
      assert.equal(evaluate(checkout, context), expected);
      assert.equal(context.env.RELEASE_TAG, automaticRuns ? 'v1.4.0' : 'v1.3.0');
      assert.equal(Boolean(evaluate(automaticPublication, context)), event === 'push', 'Manual recovery cannot update the draft with GITHUB_TOKEN');
      if (event === 'workflow_dispatch') {
        assert.ok(artifact, 'Recovery must preserve the verified APK');
        assert.equal(Boolean(evaluate(artifact.match(/        if: (.+)/)?.[1], context)), true);
        assert.ok(publish.indexOf('      - name: Save verified recovery APK') > publish.indexOf('      - name: Recheck manual notes before publishing'));
        assert.match(artifact, /if-no-files-found: error/);
        const artifactPath = artifact.match(/          path: (.+)/)?.[1];
        assert.ok(artifactPath);
        assert.equal(artifactPath.replace(/\$\{\{ (.+?) \}\}/g, (_, expression) => evaluate(expression, context)), `app/build/outputs/apk/release/sbg-scout-${context.env.RELEASE_TAG}.apk`);
      }
    }
    context.cancelled = () => true;
    assert.equal(evaluate(publishCondition, context), false);
  }
});
