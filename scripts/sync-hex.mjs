#!/usr/bin/env node
// Refreshes shared/voice-engine/upstream/ from a Hex checkout: a read-only mirror of the Hex files
// Hex Code uses, pinned to one commit in shared/voice-engine/hex-upstream.json. Never edit upstream/
// by hand; Hex Code reaches it only through shared/voice-engine/adapter/. Patches in
// shared/voice-engine/patches/*.patch are re-applied after every sync.
//
//   npm run hex:sync                         latest commit of the Hex checkout at ../hex
//   npm run hex:sync -- --hex <dir> --ref <commit>
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const engineDir = join(root, 'shared/voice-engine');
const pinPath = join(engineDir, 'hex-upstream.json');
const args = process.argv.slice(2);
const opt = (name, fallback) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : fallback; };
const hexDir = resolve(root, opt('--hex', process.env.HEX_REPO || '../hex'));
const git = (...a) => execFileSync('git', ['-C', hexDir, ...a], { encoding: 'utf8' });

if (!existsSync(join(hexDir, '.git'))) {
  console.error(`No Hex checkout at ${hexDir}. Clone https://github.com/anomalyco/hex there or pass --hex <dir>.`);
  process.exit(1);
}
const pin = JSON.parse(readFileSync(pinPath, 'utf8'));
const commit = git('rev-parse', `${opt('--ref', 'HEAD')}^{commit}`).trim();
const previous = pin.commit;

for (const { from, to } of pin.files) {
  const content = execFileSync('git', ['-C', hexDir, 'show', `${commit}:${from}`]);
  const dest = join(engineDir, to);
  mkdirSync(dirname(dest), { recursive: true });
  writeFileSync(dest, content);
}

// Android builds the same engine as Hex desktop: transcribe.cpp at the revision Hex's Cargo.toml pins
const cargo = execFileSync('git', ['-C', hexDir, 'show', `${commit}:Cargo.toml`], { encoding: 'utf8' });
const tc = cargo.match(/transcribe-cpp = \{ git = "([^"]+)", rev = "([0-9a-f]+)" \}/);
if (tc) pin.transcribeCpp = { repository: tc[1], rev: tc[2] };

// and the same Parakeet V2 model file Hex desktop downloads (its GGUF artifact constant)
const artifacts = execFileSync('git', ['-C', hexDir, 'show', `${commit}:src/transcription/artifacts.rs`], { encoding: 'utf8' });
const block = artifacts.match(/PARAKEET_V2_ARTIFACT: GgufArtifact = GgufArtifact \{([\s\S]*?)\};/);
if (block) {
  const field = name => block[1].match(new RegExp(`${name}: "?([^",\n]+)"?,`))[1];
  pin.androidModel = {
    repository: field('repository'), revision: field('revision'), filename: field('filename'),
    bytes: Number(field('bytes').replaceAll('_', '')), sha256: field('sha256'),
  };
}

const patchDir = join(engineDir, 'patches');
if (existsSync(patchDir)) {
  for (const patch of readdirSync(patchDir).filter(f => f.endsWith('.patch')).sort()) {
    try {
      execFileSync('git', ['-C', root, 'apply', '--whitespace=nowarn', join(patchDir, patch)], { stdio: 'inherit' });
      console.log(`applied ${patch}`);
    } catch {
      console.error(`Patch ${patch} no longer applies to Hex ${commit.slice(0, 9)}. Update it, then sync again.`);
      process.exit(1);
    }
  }
}

pin.commit = commit;
pin.syncedAt = new Date().toISOString().slice(0, 10);
writeFileSync(pinPath, JSON.stringify(pin, null, 2) + '\n');

console.log(`Hex ${previous ? previous.slice(0, 9) + ' → ' : ''}${commit.slice(0, 9)}`);
if (previous && previous !== commit) {
  const paths = pin.files.map(f => f.from);
  const changed = git('diff', '--stat', previous, commit, '--', ...paths).trim();
  console.log(changed || 'No changes in the mirrored files.');
}
console.log(`transcribe.cpp ${pin.transcribeCpp.rev ? pin.transcribeCpp.rev.slice(0, 9) : '(not found)'}`);
console.log(`Android model ${pin.androidModel ? pin.androidModel.filename : '(not found)'}`);
