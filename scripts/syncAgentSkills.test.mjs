import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { lstat, mkdir, mkdtemp, readFile, readdir, rename, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve, relative } from 'node:path';
import test from 'node:test';
import { syncAgentSkills } from './syncAgentSkills.mjs';

async function fixture(t) {
  const parent = resolve(tmpdir());
  const root = await mkdtemp(join(parent, 'sbg-agent-skills-'));
  t.after(async () => {
    const name = relative(parent, root);
    assert.match(name, /^sbg-agent-skills-[a-zA-Z0-9]+$/);
    await rm(root, { recursive: true, force: true });
  });
  const source = join(root, '.agents', 'skills');
  const target = join(root, '.claude', 'skills');
  await mkdir(source, { recursive: true });
  const skill = join(source, 'my-sbg-example');
  await put(join(skill, 'SKILL.md'), '---\nname: my-sbg-example\ndescription: Example workflow.\n---\n\nИнструкции.\n');
  return { root, source, target, skill, copy: join(target, 'my-sbg-example') };
}

async function put(path, content) {
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, content);
}

test('copies skills, nested references and binary assets; second run makes no changes', async (t) => {
  const { root, skill, copy } = await fixture(t);
  await put(join(skill, 'references', 'workflow.md'), 'Референс.\n');
  const binary = Buffer.from([0, 255, 13, 10, 128]);
  await put(join(skill, 'assets', 'sample.bin'), binary);
  assert.deepEqual(await syncAgentSkills({ root }), ['my-sbg-example']);
  assert.deepEqual(await readFile(join(copy, 'SKILL.md')), await readFile(join(skill, 'SKILL.md')));
  assert.equal(await readFile(join(copy, 'references', 'workflow.md'), 'utf8'), 'Референс.\n');
  assert.deepEqual(await readFile(join(copy, 'assets', 'sample.bin')), binary);
  const before = (await lstat(join(copy, 'SKILL.md'))).mtimeMs;
  assert.deepEqual(await syncAgentSkills({ root }), []);
  assert.equal((await lstat(join(copy, 'SKILL.md'))).mtimeMs, before);
  assert.deepEqual(await syncAgentSkills({ root, check: true }), []);
});

test('check reports missing outputs without creating any directory', async (t) => {
  const { root, target } = await fixture(t);
  assert.deepEqual(await syncAgentSkills({ root, check: true }), ['my-sbg-example']);
  await assert.rejects(lstat(target), { code: 'ENOENT' });
});

test('check detects drift without repairing it; sync repairs it and removes obsolete resources', async (t) => {
  const { root, copy } = await fixture(t);
  await syncAgentSkills({ root });
  await put(join(copy, 'SKILL.md'), 'Edited generated copy.\n');
  await put(join(copy, 'references', 'obsolete.md'), 'Old reference.\n');
  assert.deepEqual(await syncAgentSkills({ root, check: true }), ['my-sbg-example']);
  assert.equal(await readFile(join(copy, 'SKILL.md'), 'utf8'), 'Edited generated copy.\n');
  await syncAgentSkills({ root });
  assert.deepEqual(await syncAgentSkills({ root, check: true }), []);
  await assert.rejects(lstat(join(copy, 'references')), { code: 'ENOENT' });
});

test('removes a retired generated skill and preserves unrelated Claude skills', async (t) => {
  const { root, skill, target, copy } = await fixture(t);
  await syncAgentSkills({ root });
  await put(join(target, 'my-private', 'SKILL.md'), 'Personal skill.\n');
  await put(join(target, 'my-sbg-local', 'SKILL.md'), 'Unmanaged skill.\n');
  await rm(skill, { recursive: true });
  assert.deepEqual(await syncAgentSkills({ root, check: true }), ['my-sbg-example']);
  await syncAgentSkills({ root });
  await assert.rejects(lstat(copy), { code: 'ENOENT' });
  assert.deepEqual((await readdir(target)).sort(), ['my-private', 'my-sbg-local']);
});

test('refuses to overwrite an unmanaged skill before writing any other outputs', async (t) => {
  const { root, source, copy } = await fixture(t);
  await put(join(source, 'my-sbg-another', 'SKILL.md'), 'Another workflow.\n');
  await put(join(copy, 'SKILL.md'), 'Manual instructions.\n');
  await assert.rejects(syncAgentSkills({ root }), /unmanaged skill/);
  assert.equal(await readFile(join(copy, 'SKILL.md'), 'utf8'), 'Manual instructions.\n');
  await assert.rejects(lstat(join(root, '.claude', 'skills', 'my-sbg-another')), { code: 'ENOENT' });
});

test('invalid or incomplete sources fail before changing generated copies', async (t) => {
  const { root, source, copy } = await fixture(t);
  await syncAgentSkills({ root });
  await mkdir(join(source, 'my-sbg-incomplete'));
  await assert.rejects(syncAgentSkills({ root }), /Missing SKILL.md/);
  await rm(join(source, 'my-sbg-incomplete'), { recursive: true });
  await mkdir(join(source, 'bad-name'));
  await assert.rejects(syncAgentSkills({ root }), /Invalid shared skill directory/);
  assert.match(await readFile(join(copy, 'SKILL.md'), 'utf8'), /Инструкции/);
});

test('detects renamed resources even when the file count stays the same', async (t) => {
  const { root, skill, copy } = await fixture(t);
  await put(join(skill, 'references', 'before.md'), 'Reference.\n');
  await syncAgentSkills({ root });
  await rename(join(skill, 'references', 'before.md'), join(skill, 'references', 'after.md'));
  assert.deepEqual(await syncAgentSkills({ root, check: true }), ['my-sbg-example']);
  await syncAgentSkills({ root });
  await assert.rejects(lstat(join(copy, 'references', 'before.md')), { code: 'ENOENT' });
  assert.equal(await readFile(join(copy, 'references', 'after.md'), 'utf8'), 'Reference.\n');
});

test('rejects a linked output parent and leaves its target untouched', async (t) => {
  const { root } = await fixture(t);
  const outside = join(root, 'outside');
  await put(join(outside, 'sentinel.txt'), 'Do not change.\n');
  await symlink(outside, join(root, '.claude'), process.platform === 'win32' ? 'junction' : 'dir');
  await assert.rejects(syncAgentSkills({ root }), /not a link/);
  assert.deepEqual(await readdir(outside), ['sentinel.txt']);
  assert.equal(await readFile(join(outside, 'sentinel.txt'), 'utf8'), 'Do not change.\n');
});

test('rejects linked resources before updating generated copies', async (t) => {
  const { root, skill, copy } = await fixture(t);
  await syncAgentSkills({ root });
  const outside = join(root, 'outside');
  await mkdir(outside);
  await symlink(outside, join(skill, 'references'), process.platform === 'win32' ? 'junction' : 'dir');
  await assert.rejects(syncAgentSkills({ root }), /Links are not supported/);
  assert.match(await readFile(join(copy, 'SKILL.md'), 'utf8'), /Инструкции/);
});

test('CLI works from another working directory and returns failing status on drift or invalid arguments', async (t) => {
  const { root, target, copy } = await fixture(t);
  const script = join(root, 'scripts', 'syncAgentSkills.mjs');
  await put(script, await readFile(new URL('./syncAgentSkills.mjs', import.meta.url)));
  const run = (...argumentsList) => spawnSync(process.execPath, [script, ...argumentsList], {
    cwd: tmpdir(), encoding: 'utf8', timeout: 10_000,
  });
  let result = run('--check');
  assert.equal(result.status, 1, result.stderr);
  assert.match(result.stderr, /out of sync/);
  await assert.rejects(lstat(target), { code: 'ENOENT' });
  result = run();
  assert.equal(result.status, 0, result.stderr);
  result = run('--check');
  assert.equal(result.status, 0, result.stderr);
  await put(join(copy, 'SKILL.md'), 'Changed copy.\n');
  result = run('--check');
  assert.equal(result.status, 1, result.stderr);
  assert.equal(await readFile(join(copy, 'SKILL.md'), 'utf8'), 'Changed copy.\n');
  result = run('--unknown');
  assert.equal(result.status, 1, result.stderr);
  assert.match(result.stderr, /Usage:/);
});
