#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
//
// build.mjs — regenerate the pinned Yomitan conformance corpus.
//
// Plan 05 §A1.3: the committed corpus is produced by this committed generator, so it is
// reproducible and pinned to one upstream commit. It is a **dev tool**: it is not run by CI,
// needs no network, and requires a local Yomitan checkout only when regenerating. Tests read
// the generated files from `core/dictionary/src/test/resources/yomitan/` and never touch this
// script or the Yomitan clone.
//
// Usage:
//   node tools/yomitan-corpus/build.mjs [--yomitan <dir>] [--out <dir>] [--update] [--skip-commit-check]
//
//   --yomitan <dir>   Yomitan checkout (default: $YOMITAN_DIR or /tmp/opencode/yomitan)
//   --out <dir>       Output dir (default: core/dictionary/src/test/resources/yomitan)
//   --update          Accept changed upstream input hashes (rewrites manifest.json)
//   --skip-commit-check  Do not require the checkout HEAD to equal the pinned commit
//
// Determinism: no timestamps, fixed field order, LF-terminated UTF-8, stable source order.
// Re-running against the same checkout produces byte-identical output.

import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

// --- Pinned upstream provenance ---------------------------------------------------------
// Yomitan is GPL-3.0; the derived corpus is GPL-3.0-or-later (see 05 §A1.3 and the README).
const YOMITAN = {
    repository: 'https://github.com/yomidevs/yomitan',
    commit: 'e7334a96106a6c74e6fa75bd33a5ea1219d92712',
    commitShort: 'e7334a9',
    version: '26.10.12.0',
};
const SPDX = 'GPL-3.0-or-later';

/** Upstream fixture files whose bytes are hashed into the manifest (input provenance). */
const INPUTS = [
    'test/language/japanese-transforms.test.js',
    'test/japanese-util.test.js',
];

// --- CLI --------------------------------------------------------------------------------
const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(SCRIPT_DIR, '..', '..');

const args = process.argv.slice(2);
function flag(name) {
    const index = args.indexOf(name);
    return index >= 0;
}
function option(name, fallback) {
    const index = args.indexOf(name);
    return index >= 0 && args[index + 1] ? args[index + 1] : fallback;
}

const yomitanDir = path.resolve(option('--yomitan', process.env.YOMITAN_DIR ?? '/tmp/opencode/yomitan'));
const outDir = path.resolve(option('--out', path.join(REPO_ROOT, 'core', 'dictionary', 'src', 'test', 'resources', 'yomitan')));
const acceptInputDrift = flag('--update');
const skipCommitCheck = flag('--skip-commit-check');

// --- Helpers ---------------------------------------------------------------------------
function sha256(buffer) {
    return crypto.createHash('sha256').update(buffer).digest('hex');
}

function readText(file) {
    return fs.readFileSync(file, 'utf8');
}

/**
 * Extract a plain JavaScript array literal declared at `decl` after `from`.
 *
 * The Yomitan fixtures are data-only literals (`{term, source, rule, reasons}` tuples), so a
 * bracket-matched slice can be evaluated directly. This avoids importing the test files, which
 * `import` vitest and cannot load under plain Node. If upstream ever changes the literal shape
 * this throws with a clear message instead of silently emitting a partial corpus.
 */
function extractArray(text, from, decl) {
    const fromAt = text.indexOf(from);
    if (fromAt < 0) throw new Error(`anchor not found: ${JSON.stringify(from)}`);
    const declAt = text.indexOf(decl, fromAt);
    if (declAt < 0) throw new Error(`declaration not found after anchor: ${JSON.stringify(decl)}`);
    const open = text.indexOf('[', declAt + decl.length - 1);
    if (open < 0) throw new Error(`array literal not found after: ${JSON.stringify(decl)}`);
    const literal = scanArray(text, open);
    try {
        // eslint-disable-next-line no-new-func
        return new Function(`return (${literal});`)();
    } catch (error) {
        throw new Error(`failed to evaluate array literal after ${JSON.stringify(decl)}: ${error.message}`);
    }
}

/** Return the source slice of a balanced `[...]` starting at `open`, skipping strings/comments. */
function scanArray(text, open) {
    let depth = 0;
    let i = open;
    let inString = null;
    let escaped = false;
    let inLineComment = false;
    let inBlockComment = false;
    for (; i < text.length; i += 1) {
        const char = text[i];
        if (inLineComment) {
            if (char === '\n') inLineComment = false;
            continue;
        }
        if (inBlockComment) {
            if (char === '*' && text[i + 1] === '/') {
                inBlockComment = false;
                i += 1;
            }
            continue;
        }
        if (inString !== null) {
            if (escaped) {
                escaped = false;
            } else if (char === '\\') {
                escaped = true;
            } else if (char === inString) {
                inString = null;
            }
            continue;
        }
        if (char === '/' && text[i + 1] === '/') {
            inLineComment = true;
            i += 1;
            continue;
        }
        if (char === '/' && text[i + 1] === '*') {
            inBlockComment = true;
            i += 1;
            continue;
        }
        if (char === '"' || char === "'" || char === '`') {
            inString = char;
            continue;
        }
        if (char === '[') {
            depth += 1;
        } else if (char === ']') {
            depth -= 1;
            if (depth === 0) {
                i += 1;
                break;
            }
        }
    }
    return text.slice(open, i);
}

/** JSON.stringify one record per line, LF-terminated UTF-8, stable key order. */
function toJsonl(records) {
    return `${records.map((record) => JSON.stringify(record)).join('\n')}\n`;
}

function writeFile(file, contents) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, contents, 'utf8');
}

// --- Corpus generation -----------------------------------------------------------------
function buildTransforms(yomitanDir) {
    const source = readText(path.join(yomitanDir, 'test/language/japanese-transforms.test.js'));
    const categories = extractArray(source, 'const tests = [', 'const tests = [');
    if (!Array.isArray(categories) || categories.length === 0) {
        throw new Error('no transform categories extracted');
    }
    const records = [];
    for (const category of categories) {
        if (typeof category.category !== 'string' || typeof category.valid !== 'boolean' || !Array.isArray(category.tests)) {
            throw new Error(`unexpected transform category shape: ${JSON.stringify(category).slice(0, 120)}`);
        }
        for (const tuple of category.tests) {
            records.push({
                category: category.category,
                source: tuple.source,
                term: tuple.term,
                rule: tuple.rule ?? null,
                reasons: tuple.reasons ?? null,
                valid: category.valid,
            });
        }
    }
    return records;
}

function buildNormalization(yomitanDir) {
    const source = readText(path.join(yomitanDir, 'test/japanese-util.test.js'));

    // Ordered to follow the deinflection preprocessing pipeline (spec 03 §5).
    const ops = [
        ['convertKatakanaToHiragana', 'describe(\'convertKatakanaToHiragana\'', 'const data = ['],
        ['convertHiraganaToKatakana', 'describe(\'convertHiraganaToKatakana\'', 'const data = ['],
        ['convertHalfWidthKanaToFullWidth', 'describe(\'convertHalfWidthKanaToFullWidth\'', 'const data = ['],
        ['convertAlphanumericToFullWidth', 'describe(\'convertAlphanumericToFullWidth\'', 'const data = ['],
        ['convertAlphabeticToKana', 'describe(\'convertAlphabeticToKana\'', 'const data = ['],
        ['normalizeCombiningCharacters', 'const testCasesDakuten = [', 'const testCasesDakuten = ['],
        ['normalizeCJKCompatibilityCharacters', 'describe(\'cjk compatibility characters normalization\'', 'const testCases = ['],
        ['collapseEmphaticSequences', 'describe(\'collapseEmphaticSequences\'', 'const data = ['],
    ];

    const records = [];
    for (const [op, from, decl] of ops) {
        const rows = extractArray(source, from, decl);
        for (const row of rows) {
            records.push(normalizeRow(op, row));
        }
    }

    // The combining-mark cases are four arrays concatenated in the fixture (`:1104`); append them
    // in order after the primary array so the op stays self-contained.
    const extra = [
        ['const testCasesHandakuten = [', 'const testCasesHandakuten = ['],
        ['const testCasesIgnored = [', 'const testCasesIgnored = ['],
        ['const textCasesMisc = [', 'const textCasesMisc = ['],
    ];
    for (const [from, decl] of extra) {
        for (const row of extractArray(source, from, decl)) {
            records.push(normalizeRow('normalizeCombiningCharacters', row));
        }
    }
    return records;
}

function normalizeRow(op, row) {
    switch (op) {
        case 'convertKatakanaToHiragana':
            return { op, input: row[0], args: { keepProlongedSoundMarks: row[2] === true }, expected: row[1] };
        case 'collapseEmphaticSequences':
            return { op, input: row[0][0], args: { fullCollapse: row[0][1] === true }, expected: row[1] };
        case 'convertHiraganaToKatakana':
        case 'convertHalfWidthKanaToFullWidth':
        case 'convertAlphanumericToFullWidth':
        case 'convertAlphabeticToKana':
        case 'normalizeCombiningCharacters':
        case 'normalizeCJKCompatibilityCharacters':
            return { op, input: row[0], args: {}, expected: row[1] };
        default:
            throw new Error(`unknown normalization op: ${op}`);
    }
}

/**
 * Small hand-authored synthetic dictionary + lookup assertions (plan 05 §A1.4). Self-contained:
 * it redistributes nothing and is deterministic, so the later T2/T3 engine harness can build a
 * dictionary from it without licensing questions.
 */
const SYNTHETIC_DICT = 'komikun-synthetic-ja';
const SYNTHETIC_TERMS = [
    ['食べる', 'たべる', ['v1']],
    ['見る', 'みる', ['v1']],
    ['出る', 'でる', ['v1']],
    ['続ける', 'つづける', ['v1']],
    ['行く', 'いく', ['v5']],
    ['買う', 'かう', ['v5']],
    ['読む', 'よむ', ['v5']],
    ['飲む', 'のむ', ['v5']],
    ['話す', 'はなす', ['v5']],
    ['待つ', 'まつ', ['v5']],
    ['死ぬ', 'しぬ', ['v5']],
    ['遊ぶ', 'あそぶ', ['v5']],
    ['休む', 'やすむ', ['v5']],
    ['帰る', 'かえる', ['v5']],
    ['する', 'する', ['vs']],
    ['勉強する', 'べんきょうする', ['vs']],
    ['来る', 'くる', ['vk']],
    ['論ずる', 'ろんずる', ['vz']],
    ['高い', 'たかい', ['adj-i']],
    ['良い', 'よい', ['adj-i']],
    ['早い', 'はやい', ['adj-i']],
    ['大きい', 'おおきい', ['adj-i']],
    ['楽しい', 'たのしい', ['adj-i']],
    ['ない', 'ない', ['adj-i']],
];
const SYNTHETIC_QUERIES = [
    ['食べました', '食べる', 'たべる', ['-ます', '-た']],
    ['食べて', '食べる', 'たべる', ['-て']],
    ['食べさせられない', '食べる', 'たべる', ['causative', 'potential or passive', 'negative']],
    ['行った', '行く', 'いく', ['-た']],
    ['行きます', '行く', 'いく', ['-ます']],
    ['高くない', '高い', 'たかい', ['negative']],
    ['買えば', '買う', 'かう', ['-ば']],
    ['読みます', '読む', 'よむ', ['-ます']],
    ['しませんでした', 'する', 'する', ['-ます', 'negative', '-た']],
    ['来なかった', '来る', 'くる', ['negative', '-た']],
    ['読める', '読む', 'よむ', ['potential']],
    ['食べる', '食べる', 'たべる', []],
];

function buildLookupDict() {
    return SYNTHETIC_TERMS.map(([expression, reading, rules]) => ({
        dict: SYNTHETIC_DICT,
        expression,
        reading,
        rules,
    }));
}

function buildLookup() {
    return SYNTHETIC_QUERIES.map(([query, expression, reading, rules]) => ({
        dict: SYNTHETIC_DICT,
        query,
        expect: [{ expression, reading, rules }],
    }));
}

// --- Manifest --------------------------------------------------------------------------
function hashInput(yomitanDir, relative) {
    const bytes = fs.readFileSync(path.join(yomitanDir, relative));
    return { sha256: sha256(bytes), bytes: bytes.length };
}

function hashOutput(file) {
    const bytes = fs.readFileSync(file);
    const text = bytes.toString('utf8');
    const count = text.length === 0 ? 0 : text.split('\n').filter((line) => line.length > 0).length;
    return { sha256: sha256(bytes), bytes: bytes.length, count };
}

function buildManifest(yomitanDir, outDir, outputNames) {
    const inputs = {};
    for (const relative of INPUTS) {
        inputs[relative] = hashInput(yomitanDir, relative);
    }
    const outputs = {};
    for (const name of outputNames) {
        outputs[name] = hashOutput(path.join(outDir, name));
    }
    return {
        spdx: SPDX,
        generator: 'tools/yomitan-corpus/build.mjs',
        corpusDir: 'core/dictionary/src/test/resources/yomitan',
        yomitan: YOMITAN,
        inputs,
        outputs,
    };
}

// --- Drift check against the committed manifest ----------------------------------------
function checkInputDrift(yomitanDir, outDir) {
    const manifestFile = path.join(outDir, 'manifest.json');
    if (!fs.existsSync(manifestFile)) return;
    const existing = JSON.parse(readText(manifestFile));
    if (!existing || typeof existing.inputs !== 'object') return;
    const changed = [];
    for (const relative of INPUTS) {
        const actual = hashInput(yomitanDir, relative).sha256;
        const recorded = existing.inputs[relative]?.sha256;
        if (recorded && recorded !== actual) changed.push(relative);
    }
    if (changed.length > 0 && !acceptInputDrift) {
        console.error('Yomitan input(s) changed since the committed corpus was generated:');
        for (const relative of changed) console.error(`  - ${relative}`);
        console.error('Re-run with --update to regenerate and accept the new upstream revision.');
        process.exit(1);
    }
}

// --- Main ------------------------------------------------------------------------------
function main() {
    if (!fs.existsSync(yomitanDir)) {
        console.error(`Yomitan checkout not found: ${yomitanDir}`);
        console.error('Pass --yomitan <dir> or set YOMITAN_DIR.');
        process.exit(1);
    }

    if (!skipCommitCheck) {
        let head = null;
        try {
            head = execFileSync('git', ['-C', yomitanDir, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
        } catch {
            console.error(`warning: could not read git HEAD of ${yomitanDir}; skipping commit check`);
        }
        if (head && head !== YOMITAN.commit) {
            console.error(`Yomitan checkout HEAD is ${head}, expected ${YOMITAN.commit} (${YOMITAN.commitShort}).`);
            console.error('Check out the pinned commit, or pass --skip-commit-check.');
            process.exit(1);
        }
    }

    checkInputDrift(yomitanDir, outDir);

    const transforms = buildTransforms(yomitanDir);
    const normalization = buildNormalization(yomitanDir);
    const lookupDict = buildLookupDict();
    const lookup = buildLookup();

    const outputs = {
        'ja-transforms.jsonl': toJsonl(transforms),
        'ja-normalization.jsonl': toJsonl(normalization),
        'lookup-ja.jsonl': toJsonl(lookup),
        'lookup-ja.dict.jsonl': toJsonl(lookupDict),
    };

    fs.mkdirSync(outDir, { recursive: true });
    for (const [name, contents] of Object.entries(outputs)) {
        writeFile(path.join(outDir, name), contents);
    }

    const manifest = buildManifest(yomitanDir, outDir, Object.keys(outputs));
    writeFile(path.join(outDir, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`);

    console.log(`Wrote ${Object.keys(outputs).length} corpora + manifest to ${path.relative(REPO_ROOT, outDir)}`);
    for (const [name, record] of Object.entries(manifest.outputs)) {
        console.log(`  ${name}: ${record.count} records, ${record.bytes} bytes, sha256=${record.sha256}`);
    }
}

main();
