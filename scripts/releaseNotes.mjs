import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, appendFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { previousRelease } from './checkReleaseSigning.mjs';

export const COMMENT_MARKER = '<!-- release-notes:v1 -->';
export const NOTES_START = '<!-- release-notes:start -->';
export const NOTES_END = '<!-- release-notes:end -->';
export const RELEASE_BRANCH = 'release-please--branches--main';
export const SNAPSHOT_PATH = '.release-notes.json';
export const NOTES_PATH = 'RELEASE_NOTES.md';
export const BUNDLED_SVP_START = '<!-- bundled-svp:start -->';
export const BUNDLED_SVP_END = '<!-- bundled-svp:end -->';
export const BUNDLED_SVP_TEXT = 'Обновлён вшитый скрипт Vanilla+.';
const SHA = /^[a-f0-9]{40}$/;
const SHA256 = /^[a-f0-9]{64}$/;
const normalize = (text) => text.replace(/\r\n/g, '\n');
export const digest = (text) => createHash('sha256').update(text).digest('hex');

export function updateBundledSvpDraft(body, note) {
  const starts = body.split(BUNDLED_SVP_START).length - 1;
  const ends = body.split(BUNDLED_SVP_END).length - 1;
  assert.ok(starts <= 1 && starts === ends, 'Invalid automatic Vanilla+ draft block');
  const block = note
    ? `${BUNDLED_SVP_START}\n## Обновление Vanilla+\n\n${note}\n\n🤖 Generated with GitHub Actions\n${BUNDLED_SVP_END}`
    : '';
  if (!starts) return block ? `${body}\n\n${block}` : body;
  let start = body.indexOf(BUNDLED_SVP_START);
  const end = body.indexOf(BUNDLED_SVP_END) + BUNDLED_SVP_END.length;
  assert.ok(end > start, 'Automatic Vanilla+ draft markers are reversed');
  if (!block && body.slice(start - 2, start) === '\n\n') start -= 2;
  return body.slice(0, start) + block + body.slice(end);
}

export function validateBundledSvpNote(notes, expected) {
  const updates = notes.split('\n').filter((line) => line.startsWith(`- ${BUNDLED_SVP_TEXT}`));
  assert.deepEqual(updates, expected ? [expected] : [], 'Final Vanilla+ note must match all bundle update PRs exactly once');
  if (expected) {
    const features = notes.split(/^## /m).find((section) => section.startsWith('Возможности\n'));
    assert.ok(features?.split('\n').includes(expected), 'Vanilla+ update belongs in Возможности');
  }
}

export function extractNotes(body) {
  assert.equal(typeof body, 'string', 'Missing release PR description');
  assert.equal(body.split(NOTES_START).length, 2, 'Expected one notes start marker');
  assert.equal(body.split(NOTES_END).length, 2, 'Expected one notes end marker');
  const start = body.indexOf(NOTES_START) + NOTES_START.length;
  const end = body.indexOf(NOTES_END);
  assert.ok(end > start, 'Notes markers are reversed');
  const notes = normalize(body.slice(start, end)).trim();
  assert.ok(notes.replace(/<!--[\s\S]*?-->/g, '').trim(), 'Release notes are empty');
  assert.doesNotMatch(notes, /\b(?:TODO|TBD|FIXME)\b/i, 'Replace the notes placeholder');
  assert.doesNotMatch(notes, /^# /m, 'Version heading is generated during preparation');
  return notes;
}

export function fingerprint(tree) {
  assert.equal(tree.truncated, false, 'GitHub returned a truncated tree');
  const entries = tree.tree
    .filter((entry) => entry.type !== 'tree' && ![NOTES_PATH, SNAPSHOT_PATH].includes(entry.path))
    .map(({ path, mode, type, sha }) => ({ path, mode, type, sha }))
    .sort((left, right) => (left.path < right.path ? -1 : left.path > right.path ? 1 : 0));
  return digest(JSON.stringify(entries));
}

export function selectComment(comments) {
  const candidates = comments.filter(
    (comment) =>
      comment.body?.startsWith(COMMENT_MARKER) &&
      (comment.user?.login === 'github-actions[bot]' ||
        ['OWNER', 'MEMBER', 'COLLABORATOR'].includes(comment.author_association)),
  );
  assert.ok(candidates.length <= 1, 'Multiple trusted release notes comments');
  return candidates[0];
}

export function assertReleasePr(pull, repository, { merged = false } = {}) {
  assert.match(pull.title, /^chore\(main\): release \d+\.\d+\.\d+$/, 'Unexpected release PR title');
  assert.equal(pull.base.ref, 'main', 'Release PR must target main');
  assert.equal(pull.head.ref, RELEASE_BRANCH, 'Unexpected release branch');
  assert.equal(pull.head.repo.full_name, repository, 'Release PR must belong to this repository');
  assert.equal(
    pull.user.login,
    'github-actions[bot]',
    'Release PR must be created by Release Please',
  );
  assert.equal(pull.state, merged ? 'closed' : 'open', 'Unexpected release PR state');
  assert.equal(Boolean(pull.merged_at), merged, 'Unexpected release PR merge state');
  assert.match(pull.head.sha, SHA, 'Invalid head SHA');
  assert.match(pull.base.sha, SHA, 'Invalid base SHA');
}

export class ReleaseNotes {
  constructor(repository, { token = '', fetcher = fetch, bundledSvpPath } = {}) {
    assert.match(repository, /^[\w.-]+\/[\w.-]+$/, 'Expected owner/repository');
    this.repository = repository;
    this.token = token;
    this.fetcher = fetcher;
    this.bundledSvpPath = bundledSvpPath;
  }

  async request(path, { method = 'GET', body } = {}) {
    if (method !== 'GET') assert.ok(this.token, 'A GitHub token is required for writes');
    const headers = { Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (this.token) headers.Authorization = `Bearer ${this.token}`;
    const response = await this.fetcher(`https://api.github.com/repos/${this.repository}${path}`, {
      method,
      headers,
      redirect: 'error',
      signal: AbortSignal.timeout(30_000),
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    assert.ok(response.ok, `GitHub ${method} ${path.split('?')[0]} failed (${response.status})`);
    return response.status === 204 ? undefined : response.json();
  }

  async pages(path) {
    const values = [];
    for (let page = 1; ; page += 1) {
      const batch = await this.request(
        `${path}${path.includes('?') ? '&' : '?'}per_page=100&page=${page}`,
      );
      assert.ok(Array.isArray(batch), 'Expected a GitHub list');
      values.push(...batch);
      if (batch.length < 100) return values;
    }
  }

  async find(number, optional = false) {
    if (number !== undefined) {
      assert.ok(Number.isSafeInteger(number) && number > 0, 'Invalid PR number');
      const pull = await this.request(`/pulls/${number}`);
      assertReleasePr(pull, this.repository);
      return pull;
    }
    const pulls = (await this.pages('/pulls?state=open&base=main')).filter(
      (pull) => pull.head.ref === RELEASE_BRANCH && pull.head.repo.full_name === this.repository,
    );
    if (optional && pulls.length === 0) return undefined;
    assert.equal(pulls.length, 1, 'Expected exactly one open release PR');
    assertReleasePr(pulls[0], this.repository);
    return pulls[0];
  }

  async comment(pull) {
    return selectComment(await this.pages(`/issues/${pull.number}/comments`));
  }

  async file(path, ref) {
    const file = await this.request(
      `/contents/${encodeURIComponent(path)}?ref=${encodeURIComponent(ref)}`,
    );
    assert.equal(file.type, 'file', 'Expected a repository file');
    assert.equal(file.encoding, 'base64', 'Expected base64 repository content');
    return Buffer.from(file.content, 'base64').toString('utf8');
  }

  async state(pull) {
    const notes = extractNotes(pull.body);
    const manifest = JSON.parse(await this.file('.release-please-manifest.json', pull.head.sha));
    const version = manifest['.'];
    assert.match(version, /^\d+\.\d+\.\d+$/, 'Invalid release version');
    assert.equal(
      pull.title,
      `chore(main): release ${version}`,
      'PR title differs from release version',
    );
    const tree = await this.request(`/git/trees/${pull.head.sha}?recursive=1`);
    if (this.bundledSvpPath && pull.state === 'open') {
      validateBundledSvpNote(notes, await this.bundledSvpNote(pull, version));
    }
    return { pull, notes, version, contentSha256: fingerprint(tree) };
  }

  async bundledSvpNote(pull, version) {
    assert.ok(this.bundledSvpPath, 'Bundled SVP path is not configured');
    const previous = previousRelease(await this.pages('/releases'), `v${version}`);
    const previousRef = `refs/tags/${previous.tag_name}`;
    const filePath = `/contents/${encodeURIComponent(this.bundledSvpPath)}`;
    const before = await this.request(`${filePath}?ref=${encodeURIComponent(previousRef)}`);
    const after = await this.request(`${filePath}?ref=${pull.head.sha}`);
    for (const file of [before, after]) {
      assert.equal(file.type, 'file', 'Expected a bundled SVP file');
      assert.match(file.sha, SHA, 'Invalid bundled SVP blob SHA');
    }
    if (before.sha === after.sha) return '';
    const range = new Set();
    for (let page = 1; ; page += 1) {
      const comparison = await this.request(
        `/compare/${encodeURIComponent(previousRef)}...${pull.head.sha}?per_page=100&page=${page}`,
      );
      assert.equal(comparison.status, 'ahead', 'Release must descend from the previous stable tag');
      assert.equal(comparison.behind_by, 0, 'Release diverged from the previous stable tag');
      assert.ok(Array.isArray(comparison.commits), 'Expected release commits');
      for (const commit of comparison.commits) range.add(commit.sha);
      if (comparison.commits.length < 100) {
        assert.equal(range.size, comparison.total_commits, 'Incomplete release commit range');
        break;
      }
    }
    const history = await this.pages(
      `/commits?path=${encodeURIComponent(this.bundledSvpPath)}&sha=${pull.head.sha}`,
    );
    const changes = history.filter((commit) => range.has(commit.sha));
    assert.ok(changes.length, 'Changed bundled SVP has no update commits in the release range');
    const numbers = new Set();
    for (const commit of changes) {
      const pulls = (await this.pages(`/commits/${commit.sha}/pulls`)).filter(
        (candidate) => candidate.merged_at && candidate.base.ref === 'main' && candidate.number !== pull.number,
      );
      assert.ok(pulls.length, 'Update bundled SVP in a separate merged PR before preparing the release');
      for (const candidate of pulls) numbers.add(candidate.number);
    }
    const links = [...numbers].sort((left, right) => left - right)
      .map((number) => `[#${number}](https://github.com/${this.repository}/pull/${number})`);
    return `- ${BUNDLED_SVP_TEXT} ${links.join(', ')}`;
  }

  async ensure(pull, template) {
    const existing = await this.comment(pull);
    if (!existing) assert.ok(template.startsWith(COMMENT_MARKER), 'Invalid comment template');
    let body = existing?.body || template;
    if (this.bundledSvpPath) {
      const manifest = JSON.parse(await this.file('.release-please-manifest.json', pull.head.sha));
      body = updateBundledSvpDraft(body, await this.bundledSvpNote(pull, manifest['.']));
    }
    if (existing && body === existing.body) return existing;
    return this.request(existing ? `/issues/comments/${existing.id}` : `/issues/${pull.number}/comments`, {
      method: existing ? 'PATCH' : 'POST',
      body: { body },
    });
  }

  snapshot(state, contentSha256 = state.contentSha256) {
    return {
      schema: 2,
      repository: this.repository,
      pullRequest: state.pull.number,
      sourceHeadSha: state.pull.head.sha,
      baseSha: state.pull.base.sha,
      version: state.version,
      notesSha256: digest(state.notes),
      contentSha256,
    };
  }

  validate(snapshot, state, text, baseSha = state.pull.base.sha) {
    assert.equal(snapshot.schema, 2, 'Unknown preparation schema; prepare the release again');
    assert.equal(snapshot.repository, this.repository, 'Snapshot belongs to another repository');
    assert.equal(snapshot.pullRequest, state.pull.number, 'Snapshot belongs to another PR');
    assert.match(snapshot.sourceHeadSha, SHA, 'Invalid preparation head');
    assert.match(snapshot.notesSha256, SHA256, 'Invalid notes fingerprint');
    assert.equal(snapshot.baseSha, baseSha, 'Main changed; prepare the release again');
    assert.equal(snapshot.version, state.version, 'Release version changed');
    assert.equal(snapshot.notesSha256, digest(state.notes), 'Release notes changed');
    assert.equal(snapshot.contentSha256, state.contentSha256, 'Release contents changed');
    assert.equal(
      normalize(text),
      `# v${state.version}\n\n${state.notes}\n`,
      'Release notes differ from the PR description',
    );
  }

  async check(pull) {
    const state = await this.state(pull);
    const snapshot = JSON.parse(await this.file(SNAPSHOT_PATH, pull.head.sha));
    this.validate(snapshot, state, await this.file(NOTES_PATH, pull.head.sha));
    return state;
  }

  async publishCheck(sha) {
    assert.match(sha, SHA, 'Invalid release SHA');
    const commit = await this.request(`/git/commits/${sha}`);
    const snapshot = JSON.parse(await this.file(SNAPSHOT_PATH, sha));
    const pull = await this.request(`/pulls/${snapshot.pullRequest}`);
    assertReleasePr(pull, this.repository, { merged: true });
    assert.equal(pull.merge_commit_sha, sha, 'Release SHA does not belong to the prepared PR');
    assert.equal(commit.parents.length, 1, 'Release requires a squash merge');
    const state = await this.state({ ...pull, head: { ...pull.head, sha } });
    this.validate(snapshot, state, await this.file(NOTES_PATH, sha), commit.parents[0].sha);
    return state;
  }

  async retryTarget(tag) {
    assert.match(tag, /^v\d+\.\d+\.\d+$/, 'Expected a stable release tag');
    const reference = await this.request(`/git/ref/tags/${tag}`);
    assert.equal(reference.object.type, 'commit', 'Release tag must point directly to a commit');
    const releases = (await this.pages('/releases')).filter((release) => release.tag_name === tag);
    assert.equal(releases.length, 1, 'Expected exactly one existing release');
    const release = releases[0];
    assert.equal(release.draft, true, 'Release is already published');
    assert.equal(release.prerelease, false, 'Cannot recover a prerelease');
    assert.equal(release.name, tag, 'Unexpected release title');
    assert.deepEqual(release.assets, [], 'Release already has assets; inspect the failed publish job');
    const state = await this.publishCheck(reference.object.sha);
    assert.equal(`v${state.version}`, tag, 'Release tag differs from the prepared version');
    return { sha: reference.object.sha, tag_name: tag };
  }

  async status(pull, failOnInvalid = true) {
    let error;
    try {
      await this.check(pull);
    } catch (failure) {
      error = failure;
    }
    await this.request(`/statuses/${pull.head.sha}`, {
      method: 'POST',
      body: {
        state: error ? 'failure' : 'success',
        /* Обязательный check требует одновременно check run и commit status GitHub.
           🤖 Generated with GPT-6.1 Sol */
        context: 'check',
        description: error
          ? 'Release notes: preparation is missing or stale'
          : 'Release notes match the prepared release',
        target_url: `https://github.com/${this.repository}/pull/${pull.number}`,
      },
    });
    if (error && failOnInvalid) throw error;
    if (error) console.log(error.message);
  }

  async apply(pull, directory, paths) {
    const state = await this.state(pull);
    const files = collectFiles(directory);
    assert.deepEqual(files.sort(), [...paths].sort(), 'Unexpected or missing release materials');
    const snapshot = JSON.parse(readFileSync(resolve(directory, SNAPSHOT_PATH), 'utf8'));
    assert.equal(snapshot.sourceHeadSha, pull.head.sha, 'PR changed during preparation');
    const entries = [];
    for (const path of paths) {
      const bytes = readFileSync(resolve(directory, path));
      assert.ok(bytes.length < 10 * 1024 * 1024, 'Release material is too large');
      const blob = await this.request('/git/blobs', {
        method: 'POST',
        body: { content: bytes.toString('base64'), encoding: 'base64' },
      });
      entries.push({ path, mode: '100644', type: 'blob', sha: blob.sha });
    }
    const parent = await this.request(`/git/commits/${pull.head.sha}`);
    const tree = await this.request('/git/trees', {
      method: 'POST',
      body: { base_tree: parent.tree.sha, tree: entries },
    });
    const contents = await this.request(`/git/trees/${tree.sha}?recursive=1`);
    this.validate(
      snapshot,
      { ...state, contentSha256: fingerprint(contents) },
      readFileSync(resolve(directory, NOTES_PATH), 'utf8'),
    );
    const latest = await this.state(await this.find(pull.number));
    assert.equal(latest.pull.head.sha, pull.head.sha, 'PR changed during preparation');
    assert.equal(latest.pull.base.sha, pull.base.sha, 'Main changed during preparation');
    assert.equal(digest(latest.notes), snapshot.notesSha256, 'Notes changed during preparation');
    const commit = await this.request('/git/commits', {
      method: 'POST',
      body: {
        message: `chore: подготовить релиз ${state.version}`,
        tree: tree.sha,
        parents: [pull.head.sha],
      },
    });
    await this.request(`/git/refs/heads/${RELEASE_BRANCH}`, {
      method: 'PATCH',
      body: { sha: commit.sha, force: false },
    });
    /* После записи ref GitHub может ещё вернуть прежний head PR.
       🤖 Generated with GPT-6.1 Sol */
    const refreshedPull = await this.find(pull.number);
    await this.status({ ...refreshedPull, head: { ...refreshedPull.head, sha: commit.sha } });
    return commit.sha;
  }
}

function collectFiles(directory, prefix = '') {
  return readdirSync(resolve(directory, prefix), { withFileTypes: true }).flatMap((entry) => {
    assert.ok(!entry.isSymbolicLink(), 'Release materials cannot contain links');
    const path = prefix ? `${prefix}/${entry.name}` : entry.name;
    return entry.isDirectory() ? collectFiles(directory, path) : [path];
  });
}

export function assertLocalRelease(state, directory, artifacts) {
  const git = (...args) =>
    execFileSync('git', ['-C', directory, ...args], { encoding: 'utf8' }).trim();
  assert.equal(
    git('rev-parse', 'HEAD'),
    state.pull.head.sha,
    'Check out the current release PR head',
  );
  const changed = git('diff', '--name-only', 'HEAD').split(/\r?\n/).filter(Boolean);
  assert.ok(
    changed.every((path) => [...artifacts, NOTES_PATH, SNAPSHOT_PATH].includes(path)),
    'Unexpected local changes',
  );
}

export function renderLocal(client, state, directory, artifacts) {
  assertLocalRelease(state, directory, artifacts);
  const git = (...args) =>
    execFileSync('git', ['-C', directory, ...args], { encoding: 'utf8' }).trim();
  if (artifacts.length) git('add', '--', ...artifacts);
  const stagedTree = git('write-tree');
  const tree = execFileSync('git', ['-C', directory, 'ls-tree', '-r', '-z', stagedTree], {
    encoding: 'utf8',
  });
  const entries = tree
    .split('\0')
    .filter(Boolean)
    .map((entry) => {
      const [attributes, path] = entry.split('\t');
      const [mode, type, sha] = attributes.split(' ');
      return { path, mode, type, sha };
    });
  writeFileSync(resolve(directory, NOTES_PATH), `# v${state.version}\n\n${state.notes}\n`);
  writeFileSync(
    resolve(directory, SNAPSHOT_PATH),
    `${JSON.stringify(client.snapshot(state, fingerprint({ truncated: false, tree: entries })), null, 2)}\n`,
  );
}

export async function main(args, environment = process.env) {
  const [command = 'inspect', argument] = args;
  const config = JSON.parse(readFileSync('.github/release-notes.json', 'utf8'));
  const repository = environment.GITHUB_REPOSITORY || config.repository;
  const client = new ReleaseNotes(repository, {
    token: environment.GH_TOKEN || environment.GITHUB_TOKEN,
    bundledSvpPath: config.bundledSvpPath,
  });
  if (command === 'publish-check') return client.publishCheck(argument || environment.GITHUB_SHA);
  if (command === 'retry-target') {
    const target = await client.retryTarget(argument);
    console.log(JSON.stringify(target, null, 2));
    if (environment.GITHUB_OUTPUT)
      appendFileSync(environment.GITHUB_OUTPUT, `sha=${target.sha}\ntag_name=${target.tag_name}\n`);
    return target;
  }
  const event = environment.GITHUB_EVENT_PATH
    ? JSON.parse(readFileSync(environment.GITHUB_EVENT_PATH, 'utf8'))
    : {};
  const number = argument ? Number(argument) : event.pull_request?.number || event.issue?.number;
  if (command === 'event' && event.issue && !event.issue.pull_request) return;
  if (command === 'event' && event.pull_request?.head.ref !== RELEASE_BRANCH && !event.issue)
    return;
  let pull;
  if (command === 'event' && event.issue) {
    pull = await client.request(`/pulls/${number}`);
    if (pull.head.ref !== RELEASE_BRANCH || pull.state !== 'open') return;
    assertReleasePr(pull, repository);
  } else {
    pull = await client.find(number, command === 'ensure');
  }
  if (!pull) {
    console.log('No open release PR');
    return;
  }
  if (command === 'ensure') {
    const comment = await client.ensure(
      pull,
      readFileSync('.github/RELEASE_NOTES_TEMPLATE.md', 'utf8'),
    );
    console.log(comment.html_url);
    await client.status(pull, false);
    return;
  }
  if (command === 'event') return client.status(pull);
  if (command === 'check') return client.check(pull);
  if (command === 'render') {
    const state = await client.state(pull);
    renderLocal(client, state, process.cwd(), config.artifacts);
    return;
  }
  if (command === 'apply') {
    assert.equal(
      environment.GITHUB_ACTIONS,
      'true',
      'Apply is restricted to the preparation workflow',
    );
    const sha = await client.apply(pull, environment.RELEASE_MATERIALS, [
      NOTES_PATH,
      SNAPSHOT_PATH,
      ...config.artifacts,
    ]);
    for (const context of config.statusContexts) {
      await client.request(`/statuses/${sha}`, {
        method: 'POST',
        body: {
          context,
          state: 'success',
          description: 'Release preparation checks passed',
          target_url: `https://github.com/${repository}/actions/runs/${environment.GITHUB_RUN_ID}`,
        },
      });
    }
    if (environment.GITHUB_OUTPUT) appendFileSync(environment.GITHUB_OUTPUT, `sha=${sha}\n`);
    return;
  }
  assert.equal(command, 'inspect', 'Unknown release notes command');
  const comment = await client.comment(pull);
  let bundledSvpNote;
  if (client.bundledSvpPath) {
    const manifest = JSON.parse(await client.file('.release-please-manifest.json', pull.head.sha));
    bundledSvpNote = await client.bundledSvpNote(pull, manifest['.']);
  }
  console.log(
    JSON.stringify(
      {
        url: pull.html_url,
        number: pull.number,
        head: pull.head.sha,
        base: pull.base.sha,
        comment: comment?.html_url || null,
        bundledSvpNote,
      },
      null,
      2,
    ),
  );
  if (environment.GITHUB_OUTPUT)
    appendFileSync(environment.GITHUB_OUTPUT, `number=${pull.number}\nhead=${pull.head.sha}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main(process.argv.slice(2)).catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
