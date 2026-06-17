import assert from "node:assert/strict";
import fs from "node:fs";

const html = fs.readFileSync(new URL("../dist/rendered-markdown.html", import.meta.url), "utf8");

assert.match(html, /window\.OpenCodeRenderedMarkdown/);
assert.match(html, /function sanitizeUrl/);
assert.match(html, /getBoundingClientRect/);
assert.match(html, /language-diff/);
assert.match(html, /diff-add/);
assert.doesNotMatch(html, /<script src=/);
assert.doesNotMatch(html, /https:\/\/cdn/);
assert.doesNotMatch(html, /document\.documentElement\.scrollHeight/);

console.log("rendered-markdown bundle smoke tests passed");
