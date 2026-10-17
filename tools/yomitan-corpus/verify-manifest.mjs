#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
//
// verify-manifest.mjs — fail if the committed corpus drifts from `manifest.json`.
//
// Plan 05 §A1.3/§A4: a CI check must recompute the corpus hashes and fail on silent drift.
// The authoritative in-CI gate is the JVM test `YomitanCorpusTest` (it runs in
// `testDebugUnitTest`, which already gates PRs); this script is the standalone/developer
// equivalent and is also what a CI step can call directly.
//
// Usage:
//   node tools/yomitan-corpus/verify-manifest.mjs [--out <dir>] [--yomitan <dir>]
//
//   --out <dir>      Corpus dir (default: core/dictionary/src/test/resources/yomitan)
//   --yomitan <dir>  Also verify input hashes against a Yomitan checkout (optional)

import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(SCRIPT_DIR, '..', '..');

const args = process.argv.slice(2);
function option(name, fallback) {
    const index = args.indexOf(name);
    return index >= 0 && args[index + 1] ? args[index + 1] : fallback;
}

const outDir = path.resolve(option('--out', path.join(REPO_ROOT, 'core', 'dictionary', 'src', 'test', 'resources', 'yomitan')));
const yomitanDir = option('--yomitan', null);

const failures = [];

function sha256(buffer) {
    return crypto.createHash('sha256').update(buffer).digest('hex');
}

function checkOutputs(manifest) {
    const outputs = manifest.outputs;
    if (!outputs || typeof outputs !== 'object' || Object.keys(outputs).length === 0) {
        failures.push('manifest.json has no "outputs" records');
        return;
    }
    for (const [name, expected] of Object.entries(outputs)) {
        const file = path.join(outDir, name);
        if (!fs.existsSync(file)) {
            failures.push(`missing corpus file: ${name}`);
            continue;
        }
        const bytes = fs.readFileSync(file);
        const actualSha = sha256(bytes);
        if (actualSha !== expected.sha256) {
            failures.push(`${name}: sha256 ${actualSha} != manifest ${expected.sha256}`);
        }
        if (typeof expected.bytes === 'number' && bytes.length !== expected.bytes) {
            failures.push(`${name}: ${bytes.length} bytes != manifest ${expected.bytes}`);
        }
        if (typeof expected.count === 'number') {
            const text = bytes.toString('utf8');
            const count = text.length === 0 ? 0 : text.split('\n').filter((line) => line.length > 0).length;
            if (count !== expected.count) {
                failures.push(`${name}: ${count} records != manifest ${expected.count}`);
            }
        }
    }
}

function checkInputs(manifest) {
    if (yomitanDir === null) return;
    if (!fs.existsSync(yomitanDir)) {
        failures.push(`Yomitan checkout not found: ${yomitanDir}`);
        return;
    }
    for (const [relative, expected] of Object.entries(manifest.inputs ?? {})) {
        const file = path.join(yomitanDir, relative);
        if (!fs.existsSync(file)) {
            failures.push(`missing input: ${relative}`);
            continue;
        }
        const actual = sha256(fs.readFileSync(file));
        if (actual !== expected.sha256) {
            failures.push(`${relative}: sha256 ${actual} != manifest ${expected.sha256}`);
        }
    }
}

function main() {
    const manifestFile = path.join(outDir, 'manifest.json');
    if (!fs.existsSync(manifestFile)) {
        console.error(`manifest.json not found: ${manifestFile}`);
        process.exit(1);
    }
    const manifest = JSON.parse(fs.readFileSync(manifestFile, 'utf8'));
    checkOutputs(manifest);
    checkInputs(manifest);

    if (failures.length > 0) {
        console.error('Yomitan corpus is out of sync with manifest.json:');
        for (const failure of failures) console.error(`  - ${failure}`);
        console.error('Regenerate with: node tools/yomitan-corpus/build.mjs --update');
        process.exit(1);
    }
    const outputCount = Object.keys(manifest.outputs).length;
    console.log(`Yomitan corpus in sync: ${outputCount} files verified${yomitanDir ? ' (+ inputs)' : ''}.`);
}

main();
