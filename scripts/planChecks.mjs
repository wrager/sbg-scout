import { execFileSync } from 'node:child_process';
import { appendFileSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = fileURLToPath(new URL('../', import.meta.url));
const ANDROID = ['ktlint', 'detekt', 'lint', 'unit', 'debug', 'instr', 'release'];
const TOOLING = ['skills', 'skills-tests', 'release-files', 'release-tests', 'svp-tests', 'planner-tests', 'node-syntax'];
const ALL = [...ANDROID, ...TOOLING, 'windows', 'workflows', 'e2e', 'visual'];
const TASKS = {
  ktlint: 'ktlintCheck', detekt: 'detekt', lint: 'lintInstr', unit: 'testInstrUnitTest',
  debug: 'assembleDebug', instr: 'assembleInstr', release: 'assembleRelease',
};
const COMMANDS = {
  skills: 'node scripts/syncAgentSkills.mjs --check',
  'skills-tests': 'node --test scripts/syncAgentSkills.test.mjs',
  'release-files': 'node scripts/checkRelease.mjs',
  'release-tests': 'node --test scripts/checkRelease.test.mjs scripts/releaseNotes.test.mjs',
  'svp-tests': 'node --test scripts/updateBundledSvp.test.mjs',
  'planner-tests': 'node --test scripts/planChecks.test.mjs',
  'node-syntax': 'node scripts/checkSyntax.mjs',
  'latest-svp': 'node scripts/updateBundledSvp.mjs --check',
  windows: 'powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/checkPowerShell.ps1',
  workflows: 'actionlint -shellcheck= -pyflakes=',
};

/** Select by dependency boundary; unfamiliar files keep every offline check enabled. */
export function planChecks(files, { full = false, releasePr = false } = {}) {
  const selected = new Set(full || releasePr ? ALL : []);
  const unknownFiles = [];
  const add = (...checks) => checks.forEach((check) => selected.add(check));
  for (const file of files) {
    if (['scripts/planChecks.mjs', 'scripts/planChecks.test.mjs', '.github/workflows/ci.yml'].includes(file)) {
      add(...ALL);
    } else if (['.github/workflows/e2e.yml', 'scripts/logRunnerMemory.sh'].includes(file)) {
      add('planner-tests', 'workflows', 'e2e', 'visual');
    } else if (file === '.github/workflows/prepare-release.yml') {
      add('planner-tests', 'workflows', 'release-files', 'release-tests', 'svp-tests', 'e2e', 'visual');
    } else if (['.github/workflows/release.yml', '.github/workflows/release-notes.yml'].includes(file)) {
      add('workflows', 'release-files', 'release-tests', 'svp-tests');
    } else if (file === '.github/workflows/pr-title.yml') {
      add('workflows');
    } else if (/^(?:app\/build\.gradle\.kts|(?:build|settings)\.gradle(?:\.kts)?|gradle\.properties|gradlew(?:\.bat)?)$/.test(file) ||
      /^(?:gradle|buildSrc|build-logic)\//.test(file)) {
      add(...ANDROID, 'e2e', 'visual');
    } else if (/^app\/src\/(?:main|instr)\//.test(file)) {
      add(...ANDROID, 'e2e', 'visual');
    } else if (file.startsWith('app/src/test/')) {
      add('ktlint', 'detekt', 'lint', 'unit');
    } else if (file.startsWith('app/src/androidTest/')) {
      add('ktlint', 'detekt', 'lint', 'e2e', 'visual');
    } else if (file.startsWith('app/src/debug/')) {
      add('ktlint', 'detekt', 'debug');
    } else if (file.startsWith('app/src/release/') || file === 'app/proguard-rules.pro') {
      add('ktlint', 'detekt', 'release');
    } else if (file.startsWith('.github/images/visual-regression/') && file.endsWith('.png')) {
      add('visual');
    } else if (/^\.(?:agents|claude)\/skills\//.test(file)) {
      add('skills');
    } else if (/^scripts\/syncAgentSkills(?:\.test)?\.mjs$/.test(file)) {
      add('skills', 'skills-tests', 'node-syntax');
    } else if (/^scripts\/checkRelease(?:\.test)?\.mjs$/.test(file)) {
      add('release-files', 'release-tests', 'node-syntax');
    } else if (/^scripts\/updateBundledSvp(?:\.test)?\.mjs$/.test(file)) {
      add('svp-tests', 'node-syntax');
    } else if (['RELEASE_NOTES.md', 'version.txt', '.release-please-manifest.json'].includes(file)) {
      add('release-files');
    } else if (file === 'release-please-config.json') {
      add('node-syntax', 'release-files', 'release-tests');
    } else if (['scripts/releaseNotes.mjs', 'scripts/releaseNotes.test.mjs', 'scripts/prepareRelease.mjs', '.github/release-notes.json', '.release-notes.json'].includes(file)) {
      add('release-tests', 'node-syntax');
    } else if (file === '.github/RELEASE_NOTES_TEMPLATE.md') {
      add('release-tests');
    } else if (file === 'renovate.json') {
      add('node-syntax');
    } else if (/^scripts\/[^/]+\.ps1$/.test(file)) {
      add('windows');
    } else if (['scripts/fetchRefs.mjs', 'scripts/checkSyntax.mjs'].includes(file)) {
      add('node-syntax');
    } else if (file === '.editorconfig') {
      add('ktlint');
    } else if (file === 'detekt.yml') {
      add('detekt');
    } else if (/^docs\//.test(file) || /^\.github\/ISSUE_TEMPLATE\//.test(file) ||
      (file.startsWith('.github/images/screenshots/') && file.endsWith('.png')) ||
      ['README.md', 'AGENTS.md', 'CLAUDE.md', 'LICENSE', '.gitignore', 'app/.gitignore',
        '.github/images/icon.png', '.github/images/visual-regression/README.md'].includes(file)) {
      // These files are neither build inputs nor executable agent tooling.
    } else {
      unknownFiles.push(file);
      add(...ALL);
    }
  }
  if (releasePr) add('latest-svp');
  const checks = [...ALL, 'latest-svp'].filter((check) => selected.has(check));
  const gradleTasks = ANDROID.filter((check) => selected.has(check)).map((check) => TASKS[check]);
  const commands = checks.filter((check) => COMMANDS[check]).map((check) => COMMANDS[check]);
  if (gradleTasks.length) commands.push(`./gradlew ${gradleTasks.join(' ')}`);
  if (selected.has('e2e')) commands.push('./gradlew connectedInstrAndroidTest "-Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.github.wrager.sbgscout.e2e.visualregression.VisualRegressionTest"');
  if (selected.has('visual')) commands.push('./gradlew visualRegressionTest');
  return { checks, gradleTasks, commands, unknownFiles };
}

function git(directory, argumentsList) {
  return execFileSync('git', argumentsList, { cwd: directory, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], maxBuffer: 64 * 1024 * 1024 });
}
function names(output) {
  return output.split('\0').filter(Boolean);
}

/** PR/local ranges use merge-base; push ranges compare the exact before/after pair. */
export function changedFiles(directory, { base = 'origin/main', head = 'HEAD', workingTree = true, mergeBase = true } = {}) {
  const start = mergeBase ? git(directory, ['merge-base', base, head]).trim() : base;
  const diff = ['diff', '--name-only', '--no-renames', '-z'];
  const files = names(git(directory, [...diff, start, head, '--']));
  if (workingTree) {
    files.push(...names(git(directory, [...diff, '--cached', '--'])));
    files.push(...names(git(directory, [...diff, '--'])));
    files.push(...names(git(directory, ['ls-files', '--others', '--exclude-standard', '-z'])));
  }
  return [...new Set(files)].sort();
}

export function githubRange(eventName, event) {
  if (eventName === 'pull_request') {
    if (!event.pull_request?.base?.sha || !event.pull_request?.head?.sha) throw new Error('Missing PR diff SHAs');
    return { base: event.pull_request.base.sha, head: event.pull_request.head.sha, mergeBase: true };
  }
  if (eventName === 'push') {
    if (!event.before || !event.after) throw new Error('Missing push diff SHAs');
    return /^0+$/.test(event.before) ? { full: true } : { base: event.before, head: event.after, mergeBase: false };
  }
  throw new Error(`Unsupported CI event: ${eventName}`);
}

function main(argumentsList) {
  let base = 'origin/main';
  let full = false;
  let github = false;
  let releasePr = false;
  while (argumentsList.length) {
    const argument = argumentsList.shift();
    if (argument === '--base' && argumentsList[0] && !argumentsList[0].startsWith('--')) base = argumentsList.shift();
    else if (argument === '--full') full = true;
    else if (argument === '--github') github = true;
    else if (argument === '--release-pr') releasePr = true;
    else throw new Error('Usage: node scripts/planChecks.mjs [--base REF] [--full] [--release-pr] [--github]');
  }
  let range = { base };
  if (github) {
    const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
    range = githubRange(process.env.GITHUB_EVENT_NAME, event);
    full ||= range.full === true;
    releasePr ||= event.pull_request?.head?.ref === 'release-please--branches--main';
  }
  const files = full ? [] : changedFiles(ROOT, { ...range, workingTree: !github });
  const plan = planChecks(files, { full, releasePr });
  if (github) {
    const has = (check) => plan.checks.includes(check);
    const outputs = {
      checks: JSON.stringify(plan.checks), android: ANDROID.some(has),
      tooling: [...TOOLING, 'latest-svp'].some(has), windows: has('windows'),
      workflows: has('workflows'), e2e: has('e2e'), visual: has('visual'),
    };
    appendFileSync(process.env.GITHUB_OUTPUT, Object.entries(outputs).map(([key, value]) => `${key}=${value}\n`).join(''));
    if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY,
      `Selected checks: ${plan.checks.join(', ') || 'none (documentation only)'}.\n\nChanged paths: ${files.length}; unclassified paths: ${plan.unknownFiles.length}.\n`);
  }
  console.log(JSON.stringify(plan, null, 2));
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { main(process.argv.slice(2)); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
