// Syntax-only checks do not execute scripts or require Android tools.
import { execFileSync } from 'node:child_process';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
for (const file of readdirSync(new URL('./', import.meta.url)).filter((name) => name.endsWith('.mjs'))) {
  execFileSync(process.execPath, ['--check', `scripts/${file}`], { cwd: root, stdio: 'inherit' });
}
for (const file of ['renovate.json', 'release-please-config.json', '.release-please-manifest.json']) {
  JSON.parse(readFileSync(new URL(`../${file}`, import.meta.url), 'utf8'));
}
console.log('Node scripts and repository JSON syntax are valid.');
