import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { chmodSync, copyFileSync, mkdtempSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { changedFiles, githubRange, planChecks } from './planChecks.mjs';

const checks = (files, options) => planChecks(files, options).checks;
const runtime = checks(['app/src/main/AndroidManifest.xml']);

test('PR #65 and other ordinary documentation require no executable checks', () => {
  assert.deepEqual(checks(['AGENTS.md', 'docs/agent-setup.md']), []);
  assert.deepEqual(checks(['README.md', 'CLAUDE.md',
    '.github/images/visual-regression/README.md', '.github/ISSUE_TEMPLATE/bug_report.yml']), []);
});

test('release Markdown and version metadata are not treated as ordinary docs', () => {
  for (const file of ['RELEASE_NOTES.md', 'version.txt', '.release-please-manifest.json']) {
    assert.deepEqual(checks([file]), ['release-files']);
  }
});

test('tooling checks follow their own source and test dependencies', () => {
  const cases = [
    [['.agents/skills/scout-verify/SKILL.md', '.claude/skills/scout-verify/.generated'], ['skills']],
    [['scripts/syncAgentSkills.test.mjs'], ['skills', 'skills-tests', 'node-syntax']],
    [['scripts/checkRelease.mjs'], ['release-files', 'release-tests', 'node-syntax']],
    [['scripts/updateBundledSvp.mjs'], ['svp-tests', 'node-syntax']],
    [['scripts/releaseNotes.mjs', 'scripts/releaseNotes.test.mjs', 'scripts/prepareRelease.mjs', '.github/release-notes.json', '.release-notes.json'], ['release-tests', 'node-syntax']],
    [['.github/RELEASE_NOTES_TEMPLATE.md'], ['release-tests']],
    [['scripts/androidEnvironment.ps1', 'scripts/checkPowerShell.ps1'], ['windows']],
    [['scripts/fetchRefs.mjs', 'renovate.json'], ['node-syntax']],
  ];
  for (const [files, expected] of cases) assert.deepEqual(checks(files), expected);
});

test('test-only and variant-only changes avoid unrelated builds and emulators', () => {
  assert.deepEqual(checks(['app/src/test/java/ExampleTest.kt']), ['ktlint', 'detekt', 'lint', 'unit']);
  assert.deepEqual(checks(['app/src/androidTest/assets/page.html']), ['ktlint', 'detekt', 'lint', 'e2e', 'visual']);
  assert.deepEqual(checks(['app/src/debug/res/values/strings.xml']), ['ktlint', 'detekt', 'debug']);
  assert.deepEqual(checks(['app/proguard-rules.pro']), ['ktlint', 'detekt', 'release']);
  assert.deepEqual(checks(['.github/images/visual-regression/login-light.png']), ['visual']);
  for (const name of ['game_loading.png', 'game_settings.png', 'settings.png', 'script-manager.png']) {
    assert.deepEqual(checks([`.github/images/screenshots/${name}`]), ['visual'], name);
  }
  assert.deepEqual(checks(['.editorconfig']), ['ktlint']);
  assert.deepEqual(checks(['detekt.yml']), ['detekt']);
});

test('all shared build inputs and instrumentation configuration retain runtime coverage', () => {
  for (const file of ['build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew',
    'gradlew.bat', 'gradle/wrapper/gradle-wrapper.jar', 'gradle/gradle-daemon-jvm.properties',
    'gradle/libs.versions.toml', 'app/build.gradle.kts', 'buildSrc/src/main/kotlin/Build.kt',
    'app/src/instr/res/xml/network_security_config.xml', 'app/src/main/assets/scripts/script.user.js']) {
    assert.deepEqual(checks([file]), runtime, file);
  }
});

test('mixed diffs combine required checks without adding unrelated ones', () => {
  assert.deepEqual(checks(['README.md', 'app/src/test/Test.kt', 'scripts/updateBundledSvp.test.mjs']),
    ['ktlint', 'detekt', 'lint', 'unit', 'svp-tests', 'node-syntax']);
});

test('CI/planner changes and unclassified files select full offline coverage', () => {
  const full = checks([], { full: true });
  for (const file of ['scripts/planChecks.mjs', 'scripts/planChecks.test.mjs', '.github/workflows/ci.yml',
    'new-build-system/config.json', 'app/src/future/Example.kt', 'scripts/newTool.mjs', '.github/workflows/new.yml']) {
    assert.deepEqual(checks([file]), full, file);
  }
  assert.deepEqual(planChecks(['new.file']).unknownFiles, ['new.file']);
  assert.ok(!full.includes('latest-svp'), 'ordinary PRs must not acquire a network-dependent release gate');
});

test('release PRs keep full checks and latest SVP even when only notes changed', () => {
  const plan = planChecks(['RELEASE_NOTES.md'], { releasePr: true });
  assert.deepEqual(plan.checks, [...checks([], { full: true }), 'latest-svp']);
  assert.ok(plan.commands.includes('node scripts/updateBundledSvp.mjs --check'));
});

test('workflow changes exercise only the relevant workflow dependencies', () => {
  assert.deepEqual(checks(['.github/workflows/pr-title.yml']), ['workflows']);
  assert.deepEqual(checks(['.github/workflows/e2e.yml']), ['planner-tests', 'workflows', 'e2e', 'visual']);
  assert.deepEqual(checks(['scripts/logRunnerMemory.sh']), ['planner-tests', 'workflows', 'e2e', 'visual']);
  assert.deepEqual(checks(['.github/workflows/prepare-release.yml']), ['release-files', 'release-tests', 'svp-tests', 'planner-tests', 'workflows', 'e2e', 'visual']);
  for (const workflow of ['release.yml', 'release-notes.yml']) {
    assert.deepEqual(checks([`.github/workflows/${workflow}`]), ['release-files', 'release-tests', 'svp-tests', 'workflows']);
  }
});

test('plans expose runnable commands, with functional and visual tests separate', () => {
  const plan = planChecks(['app/src/test/Test.kt']);
  assert.deepEqual(plan.gradleTasks, ['ktlintCheck', 'detekt', 'lintInstr', 'testInstrUnitTest']);
  assert.ok(plan.commands.includes('./gradlew ktlintCheck detekt lintInstr testInstrUnitTest'));
  const full = planChecks([], { full: true });
  assert.match(full.commands.find((command) => command.includes('connectedInstrAndroidTest')), /notAnnotation=.*VisualRegressionTest/);
  assert.ok(full.commands.includes('./gradlew visualRegressionTest'));
  assert.ok(full.commands.includes('./gradlew verifyReadmeScreenshots'));
});

function repository(t) {
  const directory = mkdtempSync(join(tmpdir(), 'scout-check-plan-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const git = (...argumentsList) => execFileSync('git', ['-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
    ...argumentsList], { cwd: directory, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  const write = (file, content = 'content\n') => {
    mkdirSync(dirname(join(directory, file)), { recursive: true });
    writeFileSync(join(directory, file), content);
  };
  git('init', '-b', 'main');
  write('README.md');
  write('app/src/main/Old.kt');
  git('add', '.');
  git('commit', '-m', 'initial');
  git('branch', 'base');
  return { directory, git, write };
}

test('local diff includes the entire branch, staged/unstaged paths, deletions and untracked Unicode paths', (t) => {
  const { directory, git, write } = repository(t);
  write('docs/committed.md');
  git('add', '.');
  git('commit', '-m', 'docs');
  write('app/src/test/NewTest.kt');
  git('add', '.');
  git('commit', '-m', 'tests');
  write('README.md', 'staged\n');
  git('add', 'README.md');
  write('README.md', 'content\n'); // The staged change still matters when the worktree matches HEAD.
  rmSync(join(directory, 'app/src/main/Old.kt'));
  write('docs/новый файл.md');
  assert.deepEqual(changedFiles(directory, { base: 'base' }),
    ['README.md', 'app/src/main/Old.kt', 'app/src/test/NewTest.kt', 'docs/committed.md', 'docs/новый файл.md']);
  assert.deepEqual(changedFiles(directory, { base: 'base', workingTree: false }),
    ['app/src/test/NewTest.kt', 'docs/committed.md']);
});

test('renaming a runtime input to documentation retains the removed runtime path', (t) => {
  const { directory, git } = repository(t);
  renameSync(join(directory, 'app/src/main/Old.kt'), join(directory, 'moved.md'));
  git('add', '.');
  git('commit', '-m', 'rename');
  const files = changedFiles(directory, { base: 'base', workingTree: false });
  assert.ok(files.includes('app/src/main/Old.kt'));
  assert.ok(checks(files).includes('e2e'));
});

test('push uses exact before/after, PR uses merge-base and invalid ranges fail', (t) => {
  const { directory, git, write } = repository(t);
  git('switch', '-c', 'side');
  write('docs/side.md');
  git('add', '.'); git('commit', '-m', 'side');
  git('switch', 'main');
  write('app/src/test/MainTest.kt');
  git('add', '.'); git('commit', '-m', 'main');
  assert.deepEqual(changedFiles(directory, { base: 'side', workingTree: false }), ['app/src/test/MainTest.kt']);
  assert.deepEqual(changedFiles(directory, { base: 'side', workingTree: false, mergeBase: false }),
    ['app/src/test/MainTest.kt', 'docs/side.md']);
  assert.throws(() => changedFiles(directory, { base: 'missing-ref' }));
  assert.deepEqual(githubRange('push', { before: 'old', after: 'new' }), { base: 'old', head: 'new', mergeBase: false });
  assert.deepEqual(githubRange('push', { before: '000000', after: 'new' }), { full: true });
  assert.deepEqual(githubRange('pull_request', { pull_request: { base: { sha: 'base' }, head: { sha: 'head' } } }),
    { base: 'base', head: 'head', mergeBase: true });
  assert.throws(() => githubRange('pull_request', {}));
  assert.throws(() => githubRange('schedule', {}));
});

test('GitHub output is valid and missing diff metadata cannot produce a green skip', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'scout-plan-output-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const event = join(directory, 'event.json');
  const output = join(directory, 'output.txt');
  const script = fileURLToPath(new URL('./planChecks.mjs', import.meta.url));
  const env = { ...process.env, GITHUB_EVENT_NAME: 'push', GITHUB_EVENT_PATH: event, GITHUB_OUTPUT: output, GITHUB_STEP_SUMMARY: '' };
  writeFileSync(event, JSON.stringify({ before: '0000', after: 'new' }));
  execFileSync(process.execPath, [script, '--github'], { env });
  const outputs = Object.fromEntries(readFileSync(output, 'utf8').trim().split('\n').map((line) => {
    const index = line.indexOf('='); return [line.slice(0, index), line.slice(index + 1)];
  }));
  assert.deepEqual(JSON.parse(outputs.checks), checks([], { full: true }));
  assert.equal(outputs.android, 'true');
  writeFileSync(event, '{}');
  writeFileSync(output, '');
  const failed = spawnSync(process.execPath, [script, '--github'], { env, encoding: 'utf8' });
  assert.equal(failed.status, 1);
  assert.match(failed.stderr, /Missing push diff SHAs/);
  assert.equal(readFileSync(output, 'utf8'), '');
});

test('e2e jobs start only for the suites requested by CI or a manual run', () => {
  assert.equal(workflowJob('functional').condition, "github.event_name == 'workflow_dispatch' || inputs.functional");
  assert.equal(workflowJob('visual').condition, "github.event_name == 'workflow_dispatch' || inputs.visual");
  assert.equal(workflowJob('readme').condition, "github.event_name == 'workflow_dispatch' || inputs.visual");
});

// Exercise the actual workflow commands, with a fake Gradle executable instead of an emulator.
test('each functional job runs its own shard without visual regression tests', (t) => {
  const { directory, shell, calls } = emulatorDirectory(t);
  const shard = shardCommand(workflowJob('functional').script, 2, 4);
  execFileSync(shell, ['-c', shard], { cwd: directory });
  const [call, ...rest] = calls();
  assert.deepEqual(rest, []);
  assert.match(call, /^connectedInstrAndroidTest /);
  assert.match(call, /notAnnotation=.*VisualRegressionTest/);
  assert.match(call, /-Pandroid\.testInstrumentationRunnerArguments\.numShards=4 /);
  assert.match(call, /-Pandroid\.testInstrumentationRunnerArguments\.shardIndex=2 /);
});

test('native baselines and README screenshots run on separate emulators', (t) => {
  const { directory, shell, calls } = emulatorDirectory(t);
  execFileSync(shell, ['-c', workflowJob('visual').script], { cwd: directory });
  execFileSync(shell, ['-c', workflowJob('readme').script], { cwd: directory });
  assert.deepEqual(calls().map((call) => call.split(' ')[0]), ['visualRegressionTest', 'verifyReadmeScreenshots']);
});

test('emulator jobs fail with the Gradle exit status', (t) => {
  for (const script of [shardCommand(workflowJob('functional').script, 0, 4),
    workflowJob('visual').script, workflowJob('readme').script]) {
    const { directory, shell } = emulatorDirectory(t);
    const result = spawnSync(shell, ['-c', script], { cwd: directory, env: { ...process.env, FAIL_GRADLE: 'true' } });
    assert.equal(result.status, 7);
  }
});

test('runner memory log samples while the command runs and keeps its exit status', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'scout-runner-memory-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const bin = join(directory, 'bin');
  mkdirSync(bin);
  const fake = (name, lines, command = '') =>
    writeFileSync(join(bin, name), `#!/bin/sh\n${command}cat <<'EOF'\n${lines.join('\n')}\nEOF\n`, { mode: 0o755 });
  fake('free', ['               total        used        free      shared  buff/cache   available',
    'Mem:           15990        6000        1000          45        3017        9000',
    'Swap:           4095         512        3583'], 'echo >> free.calls\n');
  fake('ps', [' 2097152 /sdk/emulator/qemu/linux-x86_64/qemu-system-x86_64 -avd test',
    '    1024 /usr/bin/java -jar gradle.jar', ' 1048576 /sdk/emulator/qemu/linux-x86_64/qemu-system-x86_64 -avd other']);
  fake('df', ['Filesystem     1048576-blocks  Used Available Capacity Mounted on', '/dev/root  74244 50000 24244 68% /']);
  /*
   * Сэмплер начинает второй замер только после печати первого, поэтому команда
   * ждёт второго вызова free, а не фиксированное время.
   * 🤖 Generated with Claude Opus 5.5
   */
  writeFileSync(join(directory, 'work'), '#!/bin/sh\nfor attempt in $(seq 100); do\n' +
    '  [ "$(cat free.calls 2>/dev/null | wc -l)" -ge 2 ] && exit 3\n  sleep 0.1\ndone\nexit 99\n', { mode: 0o755 });
  copyFileSync(new URL('./logRunnerMemory.sh', import.meta.url), join(directory, 'logRunnerMemory.sh'));
  /*
   * Git for Windows sh ставит свой /usr/bin первым в PATH, поэтому фейковые
   * free, ps и df добавляются в начало PATH уже внутри shell.
   * 🤖 Generated with Claude Opus 5.5
   */
  const result = spawnSync(shellPath(), ['-c', 'export PATH="$PWD/bin:$PATH"; exec sh logRunnerMemory.sh ./work'], {
    cwd: directory, encoding: 'utf8', env: { ...process.env, MEMORY_LOG_INTERVAL: '0.2' },
  });
  assert.equal(result.status, 3, result.stderr);
  for (const sample of result.stdout.trim().split('\n')) {
    assert.match(sample, /^runner-memory \d\d:\d\d:\d\d used=6000M available=9000M swap=512M qemu_rss=3072M disk_free=24244M$/);
  }
});

function shellPath() {
  const gitPath = execFileSync('git', ['--exec-path'], { encoding: 'utf8' }).trim();
  return process.platform === 'win32' ? resolve(gitPath, '../../../bin/sh.exe') : 'sh';
}

function workflowJob(name) {
  const workflow = readFileSync(new URL('../.github/workflows/e2e.yml', import.meta.url), 'utf8');
  const job = workflow.match(new RegExp(`^ {2}${name}:\\r?\\n((?:(?: {4}[^\\r\\n]*)?(?:\\r?\\n|$))+)`, 'm'))?.[1];
  assert.ok(job, `e2e.yml must define the ${name} job`);
  const script = job.match(/^ {10}script: (.+)$/m)?.[1];
  assert.ok(script, `${name} must pass a single-line script to emulator-runner`);
  return { condition: job.match(/^ {4}if: (.+)$/m)?.[1], script };
}

function shardCommand(script, index, total) {
  const command = script.replaceAll('${{ strategy.job-index }}', index).replaceAll('${{ strategy.job-total }}', total);
  assert.doesNotMatch(command, /\$\{\{/, 'every workflow expression in the script must be substituted');
  return command;
}

function emulatorDirectory(t) {
  const directory = mkdtempSync(join(tmpdir(), 'scout-emulator-script-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  writeFileSync(join(directory, 'calls'), '');
  writeFileSync(join(directory, 'gradlew'), '#!/bin/sh\nprintf "%s\\n" "$*" >> calls\nif [ "$FAIL_GRADLE" = true ]; then exit 7; fi\n');
  chmodSync(join(directory, 'gradlew'), 0o755);
  mkdirSync(join(directory, 'scripts'));
  copyFileSync(new URL('./logRunnerMemory.sh', import.meta.url), join(directory, 'scripts/logRunnerMemory.sh'));
  const calls = () => readFileSync(join(directory, 'calls'), 'utf8').trim().split('\n').filter(Boolean);
  return { directory, shell: shellPath(), calls };
}
