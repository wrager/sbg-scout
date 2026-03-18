/**
 * Download and organize external reference scripts for development.
 *
 * Usage: node scripts/fetchRefs.mjs
 *
 * Downloads EUI/CUI sources and releases, OpenLayers bundle, game HTML/script,
 * Anmiles APK source code. Manual content in refs/game/dom/, refs/game/css/
 * and refs/screenshots/ is preserved.
 */

import { execSync } from 'node:child_process';
import { createWriteStream, existsSync, readdirSync } from 'node:fs';
import { copyFile, mkdir, readdir, rm, writeFile } from 'node:fs/promises';
import { basename, join, resolve } from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const ROOT = resolve(import.meta.dirname, '..');
const REFS = join(ROOT, 'refs');
const TEMP = join(REFS, '.tmp');

const URLS = {
  svpZipball: 'https://api.github.com/repos/wrager/sbg-vanilla-plus/zipball',
  svpRelease: 'https://github.com/wrager/sbg-vanilla-plus/releases/latest/download/sbg-vanilla-plus.user.js',
  euiZipball: 'https://api.github.com/repos/egorantonov/sbg-enhanced/zipball',
  cuiZipball: 'https://api.github.com/repos/nicko-v/sbg-cui/zipball',
  euiRelease: 'https://github.com/egorantonov/sbg-enhanced/releases/latest/download/eui.user.js',
  cuiRelease: 'https://github.com/egorantonov/sbg-enhanced/releases/latest/download/cui.user.js',
  olBundle: 'https://sbg-game.ru/packages/js/ol@10.6.0.js',
  gamePage: 'https://sbg-game.ru/app/',
  anmilesRepo: 'https://github.com/anmiles/sbg.git',
};

/** @type {{ name: string; location: string; source: string; status: 'ok' | 'error'; error?: string }[]} */
const manifest = [];

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function toSlash(windowsPath) {
  return windowsPath.replace(/\\/g, '/');
}

async function fetchUrl(url) {
  const headers = { 'User-Agent': 'sbg-userscripts-apk-refs-fetcher' };
  const response = await fetch(url, { redirect: 'follow', headers });
  if (!response.ok) throw new Error(`HTTP ${response.status} for ${url}`);
  return response;
}

async function downloadFile(url, dest) {
  const response = await fetchUrl(url);
  const body = response.body;
  if (!body) throw new Error(`Empty body for ${url}`);
  await mkdir(join(dest, '..'), { recursive: true });
  const nodeStream = Readable.fromWeb(body);
  await pipeline(nodeStream, createWriteStream(dest));
}

async function downloadAndExtractZip(zipUrl, destDir, subdir, files) {
  await mkdir(TEMP, { recursive: true });
  const zipPath = join(TEMP, `archive-${Date.now()}.zip`);

  await downloadFile(zipUrl, zipPath);
  await mkdir(destDir, { recursive: true });

  const extractDir = join(TEMP, `extract-${Date.now()}`);
  await mkdir(extractDir, { recursive: true });
  execSync(
    `powershell -NoProfile -Command "Expand-Archive -Path '${zipPath}' -DestinationPath '${extractDir}' -Force"`,
    { stdio: 'pipe' },
  );

  const entries = readdirSync(extractDir);
  if (entries.length === 0) throw new Error('Empty archive');
  const archiveRoot = join(extractDir, entries[0]);

  if (subdir) {
    const source = join(archiveRoot, subdir);
    if (existsSync(source)) {
      await copyRecursive(source, join(destDir, subdir));
    } else {
      const available = readdirSync(archiveRoot).join(', ');
      throw new Error(`Subdirectory "${subdir}" not found. Available: ${available}`);
    }
  } else if (files) {
    for (const file of files) {
      const source = join(archiveRoot, file);
      if (existsSync(source)) {
        await copyFile(source, join(destDir, file));
      }
    }
  }

  await rm(zipPath, { force: true });
  await rm(extractDir, { recursive: true, force: true });
}

async function copyRecursive(source, dest) {
  await mkdir(dest, { recursive: true });
  const entries = await readdir(source, { withFileTypes: true });
  for (const entry of entries) {
    const sourcePath = join(source, entry.name);
    const destPath = join(dest, entry.name);
    if (entry.isDirectory()) {
      await copyRecursive(sourcePath, destPath);
    } else {
      await copyFile(sourcePath, destPath);
    }
  }
}

function extractGameScriptUrl(html) {
  const versionMatch = html.match(/const\s+v\s*=\s*'([\d.]+)'\s*,\s*hs\s*=\s*'([^']+)'/);
  if (versionMatch) {
    return `script@${versionMatch[1]}.${versionMatch[2]}.js`;
  }
  const fallback = html.match(/((?:script|intel)@[\d.]+\.[a-f0-9.]+\.js)/);
  if (fallback) return fallback[1];
  return null;
}

function ok(name, location, source) {
  manifest.push({ name, location, source, status: 'ok' });
  console.log(`  OK: ${name}`);
}

function fail(name, source, error) {
  manifest.push({ name, location: '', source, status: 'error', error });
  console.error(`  FAIL: ${name} — ${error}`);
}

// ---------------------------------------------------------------------------
// Tasks
// ---------------------------------------------------------------------------

async function fetchSvpSources() {
  const dest = join(REFS, 'svp');
  await downloadAndExtractZip(URLS.svpZipball, dest, 'src', null);
  ok('SVP sources', 'svp/src/', URLS.svpZipball);
}

async function fetchSvpRelease() {
  const dest = join(REFS, 'releases', 'sbg-vanilla-plus.user.js');
  await downloadFile(URLS.svpRelease, dest);
  ok('SVP release', 'releases/sbg-vanilla-plus.user.js', URLS.svpRelease);
}

async function fetchEuiSources() {
  const dest = join(REFS, 'eui');
  await downloadAndExtractZip(URLS.euiZipball, dest, 'src', null);
  ok('EUI sources', 'eui/src/', URLS.euiZipball);
}

async function fetchCuiSources() {
  const dest = join(REFS, 'cui');
  await downloadAndExtractZip(URLS.cuiZipball, dest, null, ['index.js', 'styles.css']);
  ok('CUI sources', 'cui/', URLS.cuiZipball);
}

async function fetchEuiRelease() {
  const dest = join(REFS, 'releases', 'eui.user.js');
  await downloadFile(URLS.euiRelease, dest);
  ok('EUI release', 'releases/eui.user.js', URLS.euiRelease);
}

async function fetchCuiRelease() {
  const dest = join(REFS, 'releases', 'cui.user.js');
  await downloadFile(URLS.cuiRelease, dest);
  ok('CUI release', 'releases/cui.user.js', URLS.cuiRelease);
}

async function fetchOlBundle() {
  const dest = join(REFS, 'ol', 'ol.js');
  await downloadFile(URLS.olBundle, dest);
  ok('OpenLayers', 'ol/ol.js', URLS.olBundle);
}

async function fetchGameAssets() {
  const response = await fetchUrl(URLS.gamePage);
  const html = await response.text();
  const htmlDest = join(REFS, 'game', 'index.html');
  await mkdir(join(REFS, 'game'), { recursive: true });
  await writeFile(htmlDest, html, 'utf-8');
  ok('Game HTML', 'game/index.html', URLS.gamePage);

  const scriptRelativeUrl = extractGameScriptUrl(html);
  if (scriptRelativeUrl) {
    const scriptUrl = new URL(scriptRelativeUrl, URLS.gamePage).href;
    const scriptDest = join(REFS, 'game', 'script.js');
    try {
      await downloadFile(scriptUrl, scriptDest);
      ok('Game script', 'game/script.js', scriptUrl);
    } catch (error) {
      fail('Game script', scriptUrl, error.message);
    }
  } else {
    fail('Game script', URLS.gamePage, 'Could not extract script URL from HTML');
  }
}

async function fetchAnmilesSources() {
  const dest = join(REFS, 'anmiles');
  if (existsSync(dest)) {
    await rm(dest, { recursive: true, force: true });
  }
  execSync(`git clone --depth 1 "${URLS.anmilesRepo}" "${toSlash(dest)}"`, { stdio: 'pipe' });
  await rm(join(dest, '.git'), { recursive: true, force: true });
  ok('Anmiles APK', 'anmiles/', URLS.anmilesRepo);
}

function generateReadme() {
  const timestamp = new Date().toISOString();
  const rows = manifest
    .map((entry) => {
      const status = entry.status === 'ok' ? 'OK' : `FAIL: ${entry.error}`;
      return `| ${entry.name} | \`${entry.location}\` | ${status} |`;
    })
    .join('\n');

  return `# Reference Scripts

Auto-generated by \`node scripts/fetchRefs.mjs\`. Do not edit manually.

Last fetched: ${timestamp}

## Contents

| Reference | Location | Status |
|-----------|----------|--------|
${rows}

## Automatic content

Everything except \`game/dom/\`, \`game/css/\` and \`screenshots/\` is downloaded automatically.
Re-run \`node scripts/fetchRefs.mjs\` to update (manual content is preserved).

## Manual content

- \`game/dom/body.html\` — rendered DOM (from DevTools)
- \`game/css/variables.css\` — :root CSS custom properties (from DevTools)
- \`screenshots/\` — UI screenshots
`;
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

async function main() {
  console.log('Fetching references...\n');

  const manualDirs = [
    join(REFS, 'game', 'dom'),
    join(REFS, 'game', 'css'),
    join(REFS, 'screenshots'),
  ];
  const preservedPaths = [];
  for (const dir of manualDirs) {
    if (existsSync(dir)) {
      const tempDest = join(TEMP, basename(dir));
      await mkdir(TEMP, { recursive: true });
      await copyRecursive(dir, tempDest);
      preservedPaths.push({ original: dir, temp: tempDest });
    }
  }

  if (existsSync(REFS)) {
    for (const entry of readdirSync(REFS)) {
      if (entry === '.tmp') continue;
      await rm(join(REFS, entry), { recursive: true, force: true });
    }
  }

  const dirs = [
    join(REFS, 'svp'),
    join(REFS, 'eui'),
    join(REFS, 'cui'),
    join(REFS, 'ol'),
    join(REFS, 'game', 'dom'),
    join(REFS, 'game', 'css'),
    join(REFS, 'releases'),
    join(REFS, 'screenshots'),
    join(REFS, 'anmiles'),
  ];
  for (const dir of dirs) {
    await mkdir(dir, { recursive: true });
  }

  for (const { original, temp } of preservedPaths) {
    await rm(original, { recursive: true, force: true });
    await copyRecursive(temp, original);
  }

  const stubs = [
    {
      path: join(REFS, 'game', 'dom', 'body.html'),
      content: [
        '<!--',
        '  Rendered DOM of the game page.',
        '  How to get:',
        '  1. Open https://sbg-game.ru/app/ in browser',
        '  2. Open DevTools → Elements',
        '  3. Right-click <body> → Copy → Copy outerHTML',
        '  4. Replace this file contents with the result',
        '-->',
        '',
      ].join('\n'),
    },
    {
      path: join(REFS, 'game', 'css', 'variables.css'),
      content: [
        '/*',
        ' * :root CSS custom properties of the game.',
        ' * How to get:',
        ' * 1. Open https://sbg-game.ru/app/ in browser',
        ' * 2. Open DevTools → Console',
        " * 3. Run: copy([...document.styleSheets].flatMap(s => { try { return [...s.cssRules] } catch { return [] } }).filter(r => r.selectorText === ':root').map(r => r.cssText).join('\\n'))",
        ' * 4. Replace this file contents with the result',
        ' */',
        '',
      ].join('\n'),
    },
  ];
  for (const stub of stubs) {
    if (!existsSync(stub.path)) {
      await writeFile(stub.path, stub.content, 'utf-8');
    }
  }

  await Promise.allSettled([
    fetchSvpSources().catch((error) => fail('SVP sources', URLS.svpZipball, error.message)),
    fetchSvpRelease().catch((error) => fail('SVP release', URLS.svpRelease, error.message)),
    fetchEuiSources().catch((error) => fail('EUI sources', URLS.euiZipball, error.message)),
    fetchCuiSources().catch((error) => fail('CUI sources', URLS.cuiZipball, error.message)),
    fetchEuiRelease().catch((error) => fail('EUI release', URLS.euiRelease, error.message)),
    fetchCuiRelease().catch((error) => fail('CUI release', URLS.cuiRelease, error.message)),
    fetchOlBundle().catch((error) => fail('OL bundle', URLS.olBundle, error.message)),
    fetchGameAssets().catch((error) => fail('Game assets', URLS.gamePage, error.message)),
    fetchAnmilesSources().catch((error) => fail('Anmiles APK', URLS.anmilesRepo, error.message)),
  ]);

  const readme = generateReadme();
  await writeFile(join(REFS, 'README.md'), readme, 'utf-8');
  console.log('  OK: README.md');

  await rm(TEMP, { recursive: true, force: true });

  const succeeded = manifest.filter((entry) => entry.status === 'ok').length;
  const failed = manifest.filter((entry) => entry.status === 'error').length;
  console.log(`\nDone: ${succeeded} OK, ${failed} failed`);
  console.log(`Output: ${REFS}`);

  if (failed > 0) process.exit(1);
}

main();
