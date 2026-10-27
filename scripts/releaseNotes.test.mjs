import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  COMMENT_MARKER,
  NOTES_START,
  NOTES_END,
  RELEASE_BRANCH,
  ReleaseNotes,
  extractNotes,
  selectComment,
  fingerprint,
  assertReleasePr,
  renderLocal,
  digest,
  main,
  BUNDLED_SVP_START,
  BUNDLED_SVP_END,
  BUNDLED_SVP_TEXT,
  updateBundledSvpDraft,
  validateBundledSvpNote,
} from './releaseNotes.mjs';
import { prepareRelease, runCommands } from './prepareRelease.mjs';

const head = 'a'.repeat(40);
const base = 'b'.repeat(40);
const repository = 'wrager/example';
const body = `${COMMENT_MARKER}\n${NOTES_START}\n## Исправления\n\n- Исправлена читаемость панели.\n${NOTES_END}\nПодтверждено пользователем.`;
const description = `Release Please\n---\n\n## [1.3.0]\n\n${body.replace(COMMENT_MARKER, '').trim()}\n\n---\nRelease Please footer`;
const pull = {
  number: 60,
  body: description,
  title: 'chore(main): release 1.3.0',
  state: 'open',
  merged_at: null,
  user: { login: 'github-actions[bot]' },
  base: { ref: 'main', sha: base },
  head: { ref: RELEASE_BRANCH, sha: head, repo: { full_name: repository } },
};
const comment = { id: 123, body, author_association: 'OWNER', user: { login: 'wrager' } };
const tree = {
  truncated: false,
  tree: [{ path: 'src/entry.ts', mode: '100644', type: 'blob', sha: head }],
};
const state = {
  pull,
  comment,
  notes: extractNotes(body),
  version: '1.3.0',
  contentSha256: fingerprint(tree),
};
const text = `# v1.3.0\n\n${state.notes}\n`;
const client = () => new ReleaseNotes(repository);

test('preparation launches npm scripts from a directory with spaces', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'release npm '));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  writeFileSync(
    join(directory, 'package.json'),
    JSON.stringify({ scripts: { probe: 'node --version' } }),
  );
  const moduleUrl = new URL('./prepareRelease.mjs', import.meta.url).href;
  const code = `import { runCommands } from ${JSON.stringify(moduleUrl)}; runCommands([['npm', 'run', 'probe']], undefined, { cwd: ${JSON.stringify(directory)} });`;
  const output = execFileSync(
    process.execPath,
    ['--throw-deprecation', '--input-type=module', '-e', code],
    { encoding: 'utf8' },
  );
  assert.match(output, /v\d+\.\d+\.\d+/);
});

test(
  'Windows batch commands preserve spaces without deprecated shell arguments',
  { skip: process.platform !== 'win32' },
  (t) => {
    const directory = mkdtempSync(join(tmpdir(), 'release batch '));
    t.after(() => rmSync(directory, { recursive: true, force: true }));
    const command = join(directory, 'echo argument.cmd');
    writeFileSync(command, '@echo %~1\r\n');
    const moduleUrl = new URL('./prepareRelease.mjs', import.meta.url).href;
    const code = `import { runCommands } from ${JSON.stringify(moduleUrl)}; runCommands([[${JSON.stringify(command)}, 'value with spaces']]);`;
    const output = execFileSync(
      process.execPath,
      ['--throw-deprecation', '--input-type=module', '-e', code],
      { encoding: 'utf8' },
    );
    assert.match(output, /value with spaces/);
  },
);

test('extracts only publishable notes and normalizes Windows newlines', () => {
  assert.equal(extractNotes(body.replaceAll('\n', '\r\n')), state.notes);
  assert.ok(!state.notes.includes('Подтверждено'));
});
for (const invalid of [
  '',
  body.replace(NOTES_END, ''),
  body.replace(NOTES_START, `${NOTES_START}${NOTES_START}`),
  body.replace(state.notes, ''),
  body.replace(state.notes, 'TODO'),
  body.replace(state.notes, '# v1.3.0'),
]) {
  test(`rejects incomplete notes ${invalid.length}`, () =>
    assert.throws(() => extractNotes(invalid)));
}

test('rejects notes with no visible text', () => {
  assert.throws(() => extractNotes(body.replace(state.notes, '<!-- evidence only -->')), /empty/);
});
test('uses one trusted comment and ignores impersonation', () => {
  assert.equal(selectComment([{ ...comment, author_association: 'NONE' }, comment]), comment);
  assert.equal(
    selectComment([
      { ...comment, author_association: 'NONE', user: { login: 'github-actions[bot]' } },
    ]).id,
    123,
  );
  assert.throws(() => selectComment([comment, comment]), /Multiple/);
});
test('rendering outputs are excluded from the source fingerprint', () => {
  assert.equal(
    fingerprint({
      ...tree,
      tree: [
        ...tree.tree,
        { path: 'RELEASE_NOTES.md', sha: base },
        { path: '.release-notes.json', sha: base },
      ],
    }),
    fingerprint(tree),
  );
  assert.notEqual(
    fingerprint({ ...tree, tree: [{ ...tree.tree[0], sha: base }] }),
    fingerprint(tree),
  );
  assert.throws(() => fingerprint({ ...tree, truncated: true }), /truncated/);
});
test('validates a prepared release against its final notes and source tree', () => {
  client().validate(client().snapshot(state), state, text);
  client().validate(client().snapshot(state), state, text.replaceAll('\n', '\r\n'));
});
for (const [field, value] of [
  ['baseSha', head],
  ['version', '1.4.0'],
  ['notesSha256', digest('edited')],
  ['notesSha256', 'invalid'],
  ['contentSha256', digest('changed')],
  ['repository', 'another/repo'],
  ['pullRequest', 61],
  ['sourceHeadSha', 'invalid'],
  ['schema', 3],
]) {
  test(`invalidates preparation after ${field} changes`, () => {
    assert.throws(() =>
      client().validate({ ...client().snapshot(state), [field]: value }, state, text),
    );
  });
}
test('rejects notes that omit a registered fix', () => {
  assert.throws(
    () => client().validate(client().snapshot(state), state, '# v1.3.0\n\nДругой текст.\n'),
    /differ/,
  );
});
for (const mutate of [
  (value) => {
    value.user.login = 'someone';
  },
  (value) => {
    value.head.repo.full_name = 'fork/repo';
  },
  (value) => {
    value.base.ref = 'other';
  },
  (value) => {
    value.head.ref = 'feature';
  },
  (value) => {
    value.title = 'release 1.3.0';
  },
]) {
  test('rejects a non-release or foreign PR', () => {
    const value = structuredClone(pull);
    mutate(value);
    assert.throws(() => assertReleasePr(value, repository));
  });
}

function apiFixture(routes, options = {}) {
  const requests = [];
  const api = new ReleaseNotes(repository, {
    ...options,
    token: 'secret-token',
    fetcher: async (url, options) => {
      const path = url.replace(`https://api.github.com/repos/${repository}`, '');
      requests.push({ path, ...options });
      const route = routes[path];
      assert.ok(route, `Unexpected request ${path}`);
      const result = typeof route === 'function' ? route(options) : route;
      return { ok: true, status: 200, json: async () => result };
    },
  });
  return { api, requests };
}

function releaseStateRoutes() {
  const encodedFile = (content) => ({
    type: 'file',
    encoding: 'base64',
    content: Buffer.from(content).toString('base64'),
  });
  return {
    '/issues/60/comments?per_page=100&page=1': [comment],
    [`/contents/.release-please-manifest.json?ref=${head}`]: encodedFile('{".":"1.3.0"}'),
    [`/git/trees/${head}?recursive=1`]: tree,
  };
}

test('reads final notes from the release PR description instead of the draft comment', async () => {
  const finalDescription = description.replace(
    'Исправлена читаемость панели.',
    'Добавлен ручной текст.',
  );
  const { api, requests } = apiFixture(releaseStateRoutes());
  const current = await api.state({ ...pull, body: finalDescription });
  assert.equal(current.notes, '## Исправления\n\n- Добавлен ручной текст.');
  assert.ok(requests.every((request) => !request.path.startsWith('/issues/')));
});

const bundledSvpPath = 'app/src/main/assets/scripts/sbg-vanilla-plus.user.js';
const bundledBefore = `/contents/${encodeURIComponent(bundledSvpPath)}?ref=refs%2Ftags%2Fv1.2.0`;
const bundledAfter = `/contents/${encodeURIComponent(bundledSvpPath)}?ref=${head}`;
const bundleHistory = `/commits?path=${encodeURIComponent(bundledSvpPath)}&sha=${head}&per_page=100&page=1`;
const bundleComparison = `/compare/refs%2Ftags%2Fv1.2.0...${head}?per_page=100&page=1`;
const bundleCommits = ['c'.repeat(40), 'd'.repeat(40)];
const bundledNote = `- ${BUNDLED_SVP_TEXT} [#25](https://github.com/${repository}/pull/25), [#44](https://github.com/${repository}/pull/44)`;

function bundledSvpRoutes() {
  const merged = (number) => ({ ...pull, number, merged_at: '2026-10-27' });
  return {
    ...releaseStateRoutes(),
    '/releases?per_page=100&page=1': [
      { tag_name: 'v1.2.0', draft: false, prerelease: false },
      { tag_name: 'v1.2.9', draft: true, prerelease: false },
      { tag_name: 'v1.2.8', draft: false, prerelease: true },
      { tag_name: 'v2.0.0', draft: false, prerelease: false },
    ],
    [bundledBefore]: { type: 'file', sha: base },
    [bundledAfter]: { type: 'file', sha: head },
    [bundleComparison]: { status: 'ahead', behind_by: 0, total_commits: 3, commits: [...bundleCommits, head].map((sha) => ({ sha })) },
    [bundleHistory]: [...bundleCommits, base].map((sha) => ({ sha })),
    [`/commits/${bundleCommits[0]}/pulls?per_page=100&page=1`]: [merged(44)],
    [`/commits/${bundleCommits[1]}/pulls?per_page=100&page=1`]: [merged(44), merged(25), pull, { ...merged(80), base: { ref: 'other' } }],
  };
}

test('bundled SVP note groups every update PR once since the previous published stable release', async () => {
  const { api, requests } = apiFixture(bundledSvpRoutes(), { bundledSvpPath });
  assert.equal(await api.bundledSvpNote(pull, '1.3.0'), bundledNote);
  assert.ok(requests.every((request) => request.method === 'GET'));
  assert.ok(!requests.some((request) => request.path.includes(`/commits/${base}/pulls`)));
});

test('bundled SVP unchanged bytes produce no note and require no commit history', async () => {
  const routes = bundledSvpRoutes();
  routes[bundledAfter].sha = routes[bundledBefore].sha;
  const { api, requests } = apiFixture(routes, { bundledSvpPath });
  assert.equal(await api.bundledSvpNote(pull, '1.3.0'), '');
  assert.ok(!requests.some((request) => /\/(?:compare|commits)/.test(request.path)));
});

test('bundled SVP includes merged update PRs from forks and deleted source repositories', async () => {
  const routes = bundledSvpRoutes();
  routes[`/commits/${bundleCommits[0]}/pulls?per_page=100&page=1`][0].head = {
    ...pull.head, repo: { full_name: 'contributor/sbg-scout' },
  };
  routes[`/commits/${bundleCommits[1]}/pulls?per_page=100&page=1`] = [{
    ...pull, number: 25, merged_at: '2026-10-27', head: { ...pull.head, repo: null },
  }];
  const { api } = apiFixture(routes, { bundledSvpPath });
  assert.equal(await api.bundledSvpNote(pull, '1.3.0'), bundledNote);
});

test('bundled SVP reads every comparison and file-history page', async () => {
  const routes = bundledSvpRoutes();
  const comparisonPrefix = bundleComparison.slice(0, -1);
  const historyPrefix = bundleHistory.slice(0, -1);
  routes[bundleComparison] = {
    status: 'ahead', behind_by: 0, total_commits: 102,
    commits: Array.from({ length: 100 }, (_, index) => ({ sha: (index + 1).toString(16).padStart(40, '0') })),
  };
  routes[`${comparisonPrefix}2`] = { ...routes[bundleComparison], commits: bundleCommits.map((sha) => ({ sha })) };
  routes[bundleHistory] = Array.from({ length: 100 }, () => ({ sha: base }));
  routes[`${historyPrefix}2`] = bundleCommits.map((sha) => ({ sha }));
  const { api, requests } = apiFixture(routes, { bundledSvpPath });
  assert.equal(await api.bundledSvpNote(pull, '1.3.0'), bundledNote);
  assert.ok(requests.some((request) => request.path === `${comparisonPrefix}2`));
  assert.ok(requests.some((request) => request.path === `${historyPrefix}2`));
});

for (const [condition, change, message] of [
  ['diverged release', (routes) => { routes[bundleComparison].status = 'diverged'; }, /descend/],
  ['incomplete range', (routes) => { routes[bundleComparison].total_commits = 4; }, /Incomplete/],
  ['missing update history', (routes) => { routes[bundleHistory] = [{ sha: base }]; }, /no update commits/],
  ['unmerged update', (routes) => { routes[`/commits/${bundleCommits[0]}/pulls?per_page=100&page=1`] = [pull]; }, /separate merged PR/],
  ['update inside release PR', (routes) => { routes[`/commits/${bundleCommits[0]}/pulls?per_page=100&page=1`] = [{ ...pull, merged_at: '2026-10-27' }]; }, /separate merged PR/],
]) {
  test(`bundled SVP refuses ${condition}`, async () => {
    const routes = bundledSvpRoutes();
    change(routes);
    const { api } = apiFixture(routes, { bundledSvpPath });
    await assert.rejects(api.bundledSvpNote(pull, '1.3.0'), message);
  });
}

test('bundled SVP automatic draft preserves user text and removes a stale update', () => {
  const original = `${body}\nПодтверждение пользователя.\n`;
  const updated = updateBundledSvpDraft(original, bundledNote);
  assert.ok(updated.startsWith(original));
  assert.equal(updated.split(BUNDLED_SVP_START).length, 2);
  assert.equal(updateBundledSvpDraft(updated, bundledNote), updated);
  const replacement = bundledNote.replace('#44', '#45').replace('/44)', '/45)');
  assert.equal(updateBundledSvpDraft(updated, replacement), updated.replace(bundledNote, replacement));
  assert.equal(updateBundledSvpDraft(updated, ''), original);
  assert.equal(updateBundledSvpDraft(original, ''), original);
  assert.throws(() => updateBundledSvpDraft(`${BUNDLED_SVP_START}${BUNDLED_SVP_START}`, bundledNote), /Invalid automatic/);
  assert.throws(() => updateBundledSvpDraft(`${BUNDLED_SVP_END}${BUNDLED_SVP_START}`, bundledNote), /reversed/);
});

test('bundled SVP final notes require one exact grouped item in Возможности', () => {
  const final = `## Возможности\n\n${bundledNote}\n\n## Исправления\n\n- Исправлена панель.`;
  assert.doesNotThrow(() => validateBundledSvpNote(final, bundledNote));
  assert.doesNotThrow(() => validateBundledSvpNote(state.notes, ''));
  for (const invalid of [state.notes, `${final}\n${bundledNote}`, final.replace(', [#44](https://github.com/wrager/example/pull/44)', ''), `## Исправления\n\n${bundledNote}`]) {
    assert.throws(() => validateBundledSvpNote(invalid, bundledNote));
  }
  assert.throws(() => validateBundledSvpNote(final, ''), /exactly once/);
});

test('bundled SVP preparation checks final notes without rewriting the description', async () => {
  const { api, requests } = apiFixture(bundledSvpRoutes(), { bundledSvpPath });
  await assert.rejects(api.state(pull), /Final Vanilla/);
  const complete = { ...pull, body: description.replace('## Исправления', `## Возможности\n\n${bundledNote}\n\n## Исправления`) };
  assert.ok((await api.state(complete)).notes.includes(bundledNote));
  assert.ok(requests.every((request) => request.method === 'GET'));
});

test('bundled SVP draft automation updates only its owned comment block and is idempotent', async () => {
  const routes = bundledSvpRoutes();
  routes['/issues/comments/123'] = (options) => {
    const updated = { ...comment, body: JSON.parse(options.body).body };
    routes['/issues/60/comments?per_page=100&page=1'] = [updated];
    return updated;
  };
  const { api, requests } = apiFixture(routes, { bundledSvpPath });
  const updated = await api.ensure(pull, 'unused template');
  assert.equal(updated.body, updateBundledSvpDraft(comment.body, bundledNote));
  assert.deepEqual(await api.ensure(pull, 'unused template'), updated);
  const writes = requests.filter((request) => request.method !== 'GET');
  assert.equal(writes.length, 1);
  assert.equal(writes[0].path, '/issues/comments/123');
});

test('bundled SVP new rules preserve recovery of an already prepared legacy release', async () => {
  const { api, requests } = apiFixture(retryRoutes(), { bundledSvpPath });
  assert.deepEqual(await api.retryTarget('v1.3.0'), { sha: head, tag_name: 'v1.3.0' });
  assert.ok(requests.every((request) => request.method === 'GET'));
});

test('bundled SVP preparation preserves the separately committed bundle', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'release-bundle-readonly-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  mkdirSync(join(directory, 'scripts'));
  const bundle = join(directory, bundledSvpPath);
  mkdirSync(join(directory, 'app/src/main/assets/scripts'), { recursive: true });
  writeFileSync(join(directory, 'scripts/updateBundledSvp.mjs'),
    `import { writeFileSync } from 'node:fs'; if (!process.argv.includes('--check')) writeFileSync(${JSON.stringify(bundledSvpPath)}, 'rewritten by preparation');`);
  const config = JSON.parse(readFileSync(new URL('../.github/release-notes.json', import.meta.url), 'utf8'));
  const workflow = readFileSync(new URL('../.github/workflows/prepare-release.yml', import.meta.url), 'utf8');
  const workflowCommand = workflow.match(/- run: (node scripts\/updateBundledSvp\.mjs[^\r\n]*)/)?.[1];
  assert.ok(workflowCommand);
  for (const command of [config.beforeNotes[0], workflowCommand.split(' ')]) {
    writeFileSync(bundle, 'separately approved bundle');
    runCommands([command], undefined, { cwd: directory });
    assert.equal(readFileSync(bundle, 'utf8'), 'separately approved bundle');
  }
  assert.ok(!config.artifacts.includes(bundledSvpPath));
  assert.doesNotMatch(workflow, /cp release-materials\/app\/src\/main\/assets/);
});

test('final notes remain usable when the draft comment is missing or incomplete', async () => {
  for (const comments of [[], [{ ...comment, body: `${COMMENT_MARKER}\nTODO: черновик` }]]) {
    const routes = releaseStateRoutes();
    routes['/issues/60/comments?per_page=100&page=1'] = comments;
    const { api } = apiFixture(routes);
    assert.equal((await api.state(pull)).notes, state.notes);
  }
});

for (const finalDescription of [
  'Release Please\n---\n\n## [1.3.0]\n\nAutomatic notes\n\n---\nFooter',
  description.replace(state.notes, ''),
  description.replace(state.notes, 'TODO: редактируется пользователем'),
  description.replace(NOTES_START, `${NOTES_START}\n${NOTES_START}`),
]) {
  test(`draft notes cannot replace an incomplete final description ${finalDescription.length}`, async () => {
    const { api } = apiFixture(releaseStateRoutes());
    await assert.rejects(api.state({ ...pull, body: finalDescription }));
  });
}

test('draft edits and evidence outside final notes do not invalidate preparation', () => {
  const prepared = client().snapshot(state);
  const changed = structuredClone(state);
  changed.comment.body += '\nИзменён черновик.';
  changed.pull.body += '\nДополнительное подтверждение.';
  assert.doesNotThrow(() => client().validate(prepared, changed, text));
});

test('editing final notes invalidates preparation even when the draft is unchanged', () => {
  const prepared = client().snapshot(state);
  const changed = { ...state, notes: `${state.notes}\n- Новый пункт.` };
  assert.throws(() => client().validate(prepared, changed, text), /notes.*changed/i);
});

test('preparation from the comment-based process requires regeneration', () => {
  const prepared = {
    ...client().snapshot(state),
    schema: 1,
    commentId: comment.id,
    commentSha256: digest(body),
  };
  assert.throws(() => client().validate(prepared, state, text), /schema/);
});

test('inspection discovers the release PR without writes', async () => {
  const { api, requests } = apiFixture({
    '/pulls?state=open&base=main&per_page=100&page=1': [pull],
  });
  assert.equal((await api.find()).number, 60);
  assert.ok(requests.every((request) => request.method === 'GET'));
});
test('does not create a replacement PR when none exists', async () => {
  const { api, requests } = apiFixture({ '/pulls?state=open&base=main&per_page=100&page=1': [] });
  await assert.rejects(api.find(), /exactly one/);
  assert.equal(await api.find(undefined, true), undefined);
  assert.ok(requests.every((request) => request.method === 'GET'));
});
test('reads every comments page before selecting the canonical comment', async () => {
  const { api } = apiFixture({
    '/issues/60/comments?per_page=100&page=1': Array.from({ length: 100 }, () => ({
      body: 'preview',
    })),
    '/issues/60/comments?per_page=100&page=2': [comment],
  });
  assert.equal((await api.comment(pull)).id, 123);
});
test('bot updates preserve the existing manual comment', async () => {
  const { api, requests } = apiFixture({ '/issues/60/comments?per_page=100&page=1': [comment] });
  assert.equal((await api.ensure(pull, `${COMMENT_MARKER}\nnew template`)).body, body);
  assert.ok(requests.every((request) => request.method === 'GET'));
});
test('creates a comment exactly once, without altering the PR description', async () => {
  const comments = [];
  const { api, requests } = apiFixture({
    '/issues/60/comments?per_page=100&page=1': () => comments,
    '/issues/60/comments': (options) => {
      comments.push({ ...comment, body: JSON.parse(options.body).body });
      return comments[0];
    },
  });
  await api.ensure(pull, body);
  await api.ensure(pull, body);
  assert.equal(requests.filter((request) => request.method === 'POST').length, 1);
  assert.equal(
    requests.find((request) => request.method === 'POST').headers['Content-Type'],
    'application/json',
  );
});
test('API errors are reported without exposing response bodies or tokens', async () => {
  const api = new ReleaseNotes(repository, {
    token: 'secret-token',
    fetcher: async () => ({ ok: false, status: 403 }),
  });
  await assert.rejects(
    api.find(60),
    (error) => error.message.includes('403') && !error.message.includes('secret-token'),
  );
});
test('writes require authorization', async () => {
  await assert.rejects(
    client().request('/issues/60/comments', { method: 'POST', body: { body } }),
    /token/,
  );
});
test('failed preparation prevents later commands from running', () => {
  const commands = [];
  assert.throws(
    () =>
      runCommands(
        [
          ['npm', 'run', 'test'],
          ['npm', 'run', 'build'],
        ],
        (program, args) => {
          commands.push([program, args]);
          return { status: 1 };
        },
      ),
    /failed/,
  );
  assert.equal(commands.length, 1);
});
test('renders staged generated materials and rejects unexpected local changes', (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'release-notes-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const git = (...args) =>
    execFileSync('git', ['-C', directory, ...args], { encoding: 'utf8' }).trim();
  git('init');
  git('config', 'user.name', 'Test');
  git('config', 'user.email', 'test@example.com');
  git('config', 'core.autocrlf', 'false');
  mkdirSync(join(directory, 'src'));
  writeFileSync(join(directory, 'src/entry.ts'), 'source\n');
  git('add', '.');
  git('commit', '-m', 'initial');
  const localState = {
    ...state,
    pull: { ...pull, head: { ...pull.head, sha: git('rev-parse', 'HEAD') } },
  };
  renderLocal(client(), localState, directory, []);
  assert.equal(readFileSync(join(directory, 'RELEASE_NOTES.md'), 'utf8'), text);
  const snapshot = JSON.parse(readFileSync(join(directory, '.release-notes.json'), 'utf8'));
  assert.match(snapshot.contentSha256, /^[a-f0-9]{64}$/);
  writeFileSync(join(directory, 'src/entry.ts'), 'changed\n');
  assert.throws(() => renderLocal(client(), localState, directory, []), /Unexpected local/);
});

test('publish checks the merged SHA, squash parent and live final notes', async () => {
  const mergedSha = 'c'.repeat(40);
  const mergedPull = {
    ...pull,
    state: 'closed',
    merged_at: '2026-10-24',
    merge_commit_sha: mergedSha,
  };
  const file = (value) => ({
    type: 'file',
    encoding: 'base64',
    content: Buffer.from(value).toString('base64'),
  });
  const routes = {
    [`/git/commits/${mergedSha}`]: { parents: [{ sha: base }] },
    [`/contents/.release-notes.json?ref=${mergedSha}`]: file(
      JSON.stringify(client().snapshot(state)),
    ),
    '/pulls/60': mergedPull,
    '/issues/60/comments?per_page=100&page=1': [comment],
    [`/contents/.release-please-manifest.json?ref=${mergedSha}`]: file('{".":"1.3.0"}'),
    [`/git/trees/${mergedSha}?recursive=1`]: tree,
    [`/contents/RELEASE_NOTES.md?ref=${mergedSha}`]: file(text),
  };
  const { api, requests } = apiFixture(routes);
  await api.publishCheck(mergedSha);
  assert.ok(requests.every((request) => request.method === 'GET'));
  routes['/issues/60/comments?per_page=100&page=1'] = [
    { ...comment, body: body + 'Edited evidence' },
  ];
  await api.publishCheck(mergedSha);
  routes['/pulls/60'] = { ...mergedPull, body: description.replace(state.notes, 'Другой текст.') };
  await assert.rejects(api.publishCheck(mergedSha), /notes changed/);
  routes['/pulls/60'] = mergedPull;
  routes[`/git/commits/${mergedSha}`] = { parents: [{ sha: head }] };
  await assert.rejects(api.publishCheck(mergedSha), /Main changed/);
  routes[`/git/commits/${mergedSha}`] = { parents: [{ sha: base }, { sha: head }] };
  await assert.rejects(api.publishCheck(mergedSha), /squash merge/);
  routes['/pulls/60'] = { ...mergedPull, merge_commit_sha: head };
  await assert.rejects(api.publishCheck(mergedSha), /does not belong/);
});

function retryRoutes() {
  const file = (value) => ({ type: 'file', encoding: 'base64', content: Buffer.from(value).toString('base64') });
  return {
    '/git/ref/tags/v1.3.0': { object: { type: 'commit', sha: head } },
    '/releases?per_page=100&page=1': [{ tag_name: 'v1.3.0', name: 'v1.3.0', draft: true, prerelease: false, assets: [] }],
    [`/git/commits/${head}`]: { parents: [{ sha: base }] },
    [`/contents/.release-notes.json?ref=${head}`]: file(JSON.stringify(client().snapshot(state))),
    '/pulls/60': { ...pull, state: 'closed', merged_at: '2026-10-27', merge_commit_sha: head },
    [`/contents/.release-please-manifest.json?ref=${head}`]: file('{".":"1.3.0"}'),
    [`/git/trees/${head}?recursive=1`]: tree,
    [`/contents/RELEASE_NOTES.md?ref=${head}`]: file(text),
  };
}

test('recovery resolves an empty draft to its original prepared commit without writes', async () => {
  const routes = retryRoutes();
  const target = routes['/releases?per_page=100&page=1'];
  routes['/releases?per_page=100&page=1'] = Array.from({ length: 100 }, () => ({ tag_name: 'v9.0.0' }));
  routes['/releases?per_page=100&page=2'] = target;
  const { api, requests } = apiFixture(routes);
  assert.deepEqual(await api.retryTarget('v1.3.0'), { sha: head, tag_name: 'v1.3.0' });
  assert.ok(requests.every((request) => request.method === 'GET'));
});

for (const [condition, change, message] of [
  ['missing release', (routes) => { routes['/releases?per_page=100&page=1'] = []; }, /existing release/],
  ['published release', (routes) => { routes['/releases?per_page=100&page=1'][0].draft = false; }, /already published/],
  ['prerelease', (routes) => { routes['/releases?per_page=100&page=1'][0].prerelease = true; }, /prerelease/],
  ['changed title', (routes) => { routes['/releases?per_page=100&page=1'][0].name = 'Other title'; }, /title/],
  ['existing assets', (routes) => { routes['/releases?per_page=100&page=1'][0].assets = [{ name: 'app.apk' }]; }, /already has assets/],
  ['annotated tag', (routes) => { routes['/git/ref/tags/v1.3.0'].object.type = 'tag'; }, /directly to a commit/],
  ['different commit', (routes) => { routes['/pulls/60'].merge_commit_sha = base; }, /does not belong/],
  ['edited notes', (routes) => { routes['/pulls/60'].body = description.replace(state.notes, 'Изменённые notes.'); }, /notes changed/],
  ['changed source', (routes) => { routes[`/git/trees/${head}?recursive=1`] = { ...tree, tree: [{ ...tree.tree[0], sha: base }] }; }, /contents changed/],
]) {
  test(`recovery rejects ${condition}`, async () => {
    const routes = retryRoutes();
    change(routes);
    const { api, requests } = apiFixture(routes);
    await assert.rejects(api.retryTarget('v1.3.0'), message);
    assert.ok(requests.every((request) => request.method === 'GET'));
  });
}

test('recovery rejects unsafe or nonstable tags before requesting GitHub', async () => {
  const { api, requests } = apiFixture({});
  for (const tag of ['', 'main', 'v1.3.0-beta', 'v1.3.0\nsha=other', 'v1.3.0/other']) {
    await assert.rejects(api.retryTarget(tag), /stable release tag/);
  }
  assert.deepEqual(requests, []);
});

test('recovery rejects a tag that differs from the prepared version', async () => {
  const routes = retryRoutes();
  routes['/git/ref/tags/v1.3.1'] = routes['/git/ref/tags/v1.3.0'];
  const release = routes['/releases?per_page=100&page=1'][0];
  release.tag_name = release.name = 'v1.3.1';
  const { api } = apiFixture(routes);
  await assert.rejects(api.retryTarget('v1.3.1'), /differs from the prepared version/);
});

test('recovery command exports the validated target for the publish job', async (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'release-recovery-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const output = join(directory, 'output');
  const { api } = apiFixture(retryRoutes());
  t.mock.method(globalThis, 'fetch', api.fetcher);
  assert.deepEqual(await main(['retry-target', 'v1.3.0'], { GITHUB_REPOSITORY: repository, GITHUB_OUTPUT: output }), { sha: head, tag_name: 'v1.3.0' });
  assert.equal(readFileSync(output, 'utf8'), `sha=${head}\ntag_name=v1.3.0\n`);
});

test('final notes edits set a failing required status instead of leaving a green check', async () => {
  const { api, requests } = apiFixture({ [`/statuses/${head}`]: {} });
  api.check = async () => {
    throw new Error('Notes changed');
  };
  await assert.rejects(api.status(pull), /Notes changed/);
  const sent = JSON.parse(requests[0].body);
  assert.equal(sent.context, 'check');
  assert.equal(sent.state, 'failure');
});

test('apply accepts only generated files and uses a non-forced branch update', async (t) => {
  const directory = mkdtempSync(join(tmpdir(), 'release-materials-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  writeFileSync(join(directory, 'RELEASE_NOTES.md'), text);
  writeFileSync(join(directory, '.release-notes.json'), JSON.stringify(client().snapshot(state)));
  const newTree = 'd'.repeat(40);
  const newCommit = 'e'.repeat(40);
  const { api, requests } = apiFixture({
    '/git/blobs': { sha: head },
    [`/git/commits/${head}`]: { tree: { sha: base } },
    '/git/trees': { sha: newTree },
    [`/git/trees/${newTree}?recursive=1`]: tree,
    '/git/commits': { sha: newCommit },
    [`/git/refs/heads/${RELEASE_BRANCH}`]: {},
  });
  api.state = async () => state;
  api.find = async () => pull;
  api.status = async () => undefined;
  assert.equal(
    await api.apply(pull, directory, ['RELEASE_NOTES.md', '.release-notes.json']),
    newCommit,
  );
  const update = requests.find((request) => request.method === 'PATCH');
  assert.deepEqual(JSON.parse(update.body), { sha: newCommit, force: false });
  assert.equal(
    JSON.parse(requests.find((request) => request.path === '/git/trees').body).base_tree,
    base,
  );
  writeFileSync(join(directory, 'unapproved.mjs'), 'execute();');
  const count = requests.length;
  await assert.rejects(
    api.apply(pull, directory, ['RELEASE_NOTES.md', '.release-notes.json']),
    /Unexpected/,
  );
  assert.equal(requests.length, count);
});

for (const response of ['fresh', 'stale', 'changed notes', 'changed base']) {
  test(`apply validates its created commit after a ${response} PR response`, async (t) => {
    const directory = mkdtempSync(join(tmpdir(), 'release-status-'));
    t.after(() => rmSync(directory, { recursive: true, force: true }));
    const snapshot = client().snapshot(state);
    writeFileSync(join(directory, 'RELEASE_NOTES.md'), text);
    writeFileSync(join(directory, '.release-notes.json'), JSON.stringify(snapshot));
    const newTree = 'd'.repeat(40);
    const newCommit = 'e'.repeat(40);
    let updated = false;
    const routes = {
      ...releaseStateRoutes(),
      '/pulls/60': () => {
        if (!updated || response === 'stale') return pull;
        const current = structuredClone(pull);
        current.head.sha = newCommit;
        if (response === 'changed notes')
          current.body = description.replace(state.notes, 'Changed');
        if (response === 'changed base') current.base.sha = 'c'.repeat(40);
        return current;
      },
      '/git/blobs': { sha: head },
      [`/git/commits/${head}`]: { tree: { sha: base } },
      '/git/trees': { sha: newTree },
      [`/git/trees/${newTree}?recursive=1`]: tree,
      '/git/commits': { sha: newCommit },
      [`/git/refs/heads/${RELEASE_BRANCH}`]: () => {
        updated = true;
        return {};
      },
      [`/git/trees/${newCommit}?recursive=1`]: tree,
      [`/statuses/${head}`]: {},
      [`/statuses/${newCommit}`]: {},
    };
    const preparedFiles = {
      '.release-please-manifest.json': '{".":"1.3.0"}',
      '.release-notes.json': JSON.stringify(snapshot),
      'RELEASE_NOTES.md': text,
    };
    for (const [path, content] of Object.entries(preparedFiles)) {
      routes[`/contents/${encodeURIComponent(path)}?ref=${newCommit}`] = {
        type: 'file',
        encoding: 'base64',
        content: Buffer.from(content).toString('base64'),
      };
    }
    const { api, requests } = apiFixture(routes);
    const fetcher = api.fetcher;
    api.fetcher = async (url, options) =>
      url.endsWith(`/contents/.release-notes.json?ref=${head}`)
        ? { ok: false, status: 404 }
        : fetcher(url, options);
    const applying = api.apply(pull, directory, ['RELEASE_NOTES.md', '.release-notes.json']);
    const invalid = response.startsWith('changed');
    if (invalid) await assert.rejects(applying, /Release notes changed|Main changed/);
    else assert.equal(await applying, newCommit);
    const statuses = requests.filter((request) => request.path.startsWith('/statuses/'));
    assert.equal(statuses.length, 1);
    assert.equal(statuses[0].path, `/statuses/${newCommit}`);
    assert.equal(JSON.parse(statuses[0].body).state, invalid ? 'failure' : 'success');
  });
}

for (const field of ['head', 'base', 'notes']) {
  test(`does not update the branch when ${field} changes during preparation`, async (t) => {
    const directory = mkdtempSync(join(tmpdir(), 'release-race-'));
    t.after(() => rmSync(directory, { recursive: true, force: true }));
    writeFileSync(join(directory, 'RELEASE_NOTES.md'), text);
    writeFileSync(join(directory, '.release-notes.json'), JSON.stringify(client().snapshot(state)));
    const { api, requests } = apiFixture({
      '/git/blobs': { sha: head },
      [`/git/commits/${head}`]: { tree: { sha: base } },
      '/git/trees': { sha: head },
      [`/git/trees/${head}?recursive=1`]: tree,
    });
    const changed = structuredClone(state);
    if (field === 'notes') changed.notes += 'Changed';
    else changed.pull[field].sha = 'c'.repeat(40);
    let reads = 0;
    api.state = async () => (reads++ ? changed : state);
    api.find = async () => pull;
    await assert.rejects(
      api.apply(pull, directory, ['RELEASE_NOTES.md', '.release-notes.json']),
      /changed during preparation/,
    );
    assert.ok(
      requests.every((request) => request.method !== 'PATCH' && request.path !== '/git/commits'),
    );
  });
}

for (const scenario of [
  'success',
  'materials ready',
  'wrong checkout',
  'edited draft',
  'edited notes',
  'failed checks',
]) {
  test(`preparation coordinates generation, rendering and checks: ${scenario}`, async (t) => {
    const directory = mkdtempSync(join(tmpdir(), 'release-prepare-'));
    t.after(() => rmSync(directory, { recursive: true, force: true }));
    const git = (...args) =>
      execFileSync('git', ['-C', directory, ...args], { encoding: 'utf8' }).trim();
    git('init');
    git('config', 'user.name', 'Test');
    git('config', 'user.email', 'test@example.com');
    git('config', 'core.autocrlf', 'false');
    mkdirSync(join(directory, '.github'));
    writeFileSync(join(directory, 'materials.txt'), 'old\n');
    writeFileSync(
      join(directory, '.github/release-notes.json'),
      JSON.stringify({
        repository,
        android: false,
        artifacts: ['materials.txt'],
        beforeNotes: [['node', 'generate']],
        checks: [
          ['node', 'verify'],
          ['node', 'later'],
        ],
      }),
    );
    git('add', '.');
    git('commit', '-m', 'fixture');
    const releasePull = {
      ...pull,
      head: { ...pull.head, sha: scenario === 'wrong checkout' ? head : git('rev-parse', 'HEAD') },
    };
    const commands = [];
    const routes = {
      '/pulls/60': () => ({
        ...releasePull,
        body:
          scenario === 'edited notes' && commands.length
            ? description.replace(state.notes, 'Изменённые заметки.')
            : description,
      }),
      '/issues/60/comments?per_page=100&page=1': () => [
        {
          ...comment,
          body: scenario === 'edited draft' && commands.length ? `${body}Changed` : body,
        },
      ],
      [`/contents/.release-please-manifest.json?ref=${releasePull.head.sha}`]: {
        type: 'file',
        encoding: 'base64',
        content: Buffer.from('{".":"1.3.0"}').toString('base64'),
      },
      [`/git/trees/${releasePull.head.sha}?recursive=1`]: tree,
    };
    const { api } = apiFixture(routes);
    const preparing = prepareRelease(60, {
      directory,
      environment: {},
      materialsReady: scenario === 'materials ready',
      fetcher: api.fetcher,
      runner: (_program, args, options) => {
        assert.equal(options.cwd, directory);
        commands.push(args[0]);
        if (args[0] === 'generate') writeFileSync(join(directory, 'materials.txt'), 'new\n');
        else assert.equal(readFileSync(join(directory, 'RELEASE_NOTES.md'), 'utf8'), text);
        return { status: scenario === 'failed checks' && args[0] === 'verify' ? 1 : 0 };
      },
    });
    if (scenario === 'materials ready') {
      await preparing;
      assert.deepEqual(commands, ['verify', 'later']);
      assert.equal(git('show', ':materials.txt'), 'old');
    } else if (scenario === 'success' || scenario === 'edited draft') {
      await preparing;
      assert.deepEqual(commands, ['generate', 'verify', 'later']);
      assert.equal(git('show', ':materials.txt'), 'new');
    } else {
      await assert.rejects(
        preparing,
        scenario === 'wrong checkout'
          ? /release PR head/
          : scenario === 'edited notes'
            ? /Notes changed/
            : /preparation failed/,
      );
      assert.deepEqual(
        commands,
        scenario === 'wrong checkout'
          ? []
          : scenario === 'edited notes'
            ? ['generate']
            : ['generate', 'verify'],
      );
    }
  });
}
