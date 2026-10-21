const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createHash } = require('node:crypto');
const preview = require('../app/src/main/assets/markdown/preview.js');
const assets = path.join(__dirname, '../app/src/main/assets/markdown');
const html = (markdown, options = {}) => preview.toHtml({ id: 'revision', markdown, tasks: [], images: [], editable: true, ...options });

test('inline, display, bracket and fenced math render actual KaTeX output', () => {
    for (const source of ['$x^2$', '$$\\frac{1}{2}$$', '$$\n\\sum_{i=1}^n i\n$$', '\\(x+1\\)', '\\[\\sqrt{x}\\]', '```math\n\\frac{1}{2}\n```', '~~~latex\nx^2\n~~~']) {
        assert.match(html(source), /class="katex/);
        assert.match(html(source), /<math /);
    }
    assert.match(html('$$\n\\begin{aligned}a &= b \\\\ c &= d\\end{aligned}\n$$'), /mtable/);
    assert.match(html('中文 $x$ 紧邻文字'), /中文 <eq>/);
});

test('currency, escaped dollars and code examples remain literal', () => {
    for (const source of ['价格 $5 和 $10', '`$x$`', '\\$x\\$', '```text\n$x$\n```', '    $$x$$', '没有闭合 $x']) {
        assert.doesNotMatch(html(source), /class="katex/);
    }
});

test('Mermaid is recognized only in a language fence, including nested and tilde fences', () => {
    for (const source of ['```mermaid\ngraph TD\nA-->B\n```', '~~~MERMAID\nsequenceDiagram\nA->>B: hi\n~~~', '> ```mermaid\n> graph TD\n> A-->B\n> ```', '- ```mermaid\n  graph TD\n  A-->B\n  ```']) {
        assert.match(html(source), /class="diagram"/);
    }
    assert.doesNotMatch(html('`mermaid`\n\n```text\ngraph TD\nA-->B\n```'), /class="diagram"/);
    assert.match(html('```mermaid\n</pre><script>bad()</script>\n```'), /&lt;script&gt;/);
    assert.doesNotMatch(html('```mermaid\n</pre><script>bad()</script>\n```'), /<script>/);
});

test('the bundled Mermaid runtime parses diagrams offline and rejects invalid syntax', async () => {
    const context = { console, setTimeout, clearTimeout, TextEncoder, TextDecoder, URL, structuredClone };
    vm.runInNewContext(fs.readFileSync(path.join(assets, 'vendor/mermaid.min.js'), 'utf8'), context);
    context.mermaid.initialize({ startOnLoad: false, securityLevel: 'strict' });
    const graph = await context.mermaid.parse('flowchart TD\nA-->B');
    assert.equal(graph.diagramType, 'flowchart-v2');
    const sequence = await context.mermaid.parse('sequenceDiagram\nAlice->>Bob: Hello');
    assert.equal(sequence.diagramType, 'sequence');
    await assert.rejects(context.mermaid.parse('not a diagram'));
    await assert.rejects(context.mermaid.parse('flowchart TD\nA-->>>>>'));
});

test('malformed math preserves source and following text without throwing', () => {
    assert.match(html('$\\frac{$\n\n后续正文'), /katex-error/);
    assert.match(html('$\\frac{$\n\n后续正文'), /后续正文/);
});

test('HTML, unsafe links and trusted KaTeX extensions cannot inject active content', () => {
    const output = html('<script>alert(1)</script> <img src=x onerror=alert(1)> [bad](javascript:alert) $\\href{javascript:alert(1)}{bad}$ $\\includegraphics{https://example.com/track}$');
    assert.doesNotMatch(output, /<script|<img|href="javascript:/);
    assert.match(html('<u>下划线</u> ~~删除~~ **加粗**'), /<u>下划线<\/u>/);
    assert.doesNotMatch(html('<u onclick="bad()">文字</u>'), /<u onclick/);
});

test('task actions use native offsets and old checked state, preserving rich labels', () => {
    const output = html('- [ ] 同名 $x$\n- [X] **同名**', { tasks: [{ line: 0, offset: 3, checked: false }, { line: 1, offset: 17, checked: true }] });
    assert.match(output, /offset=3&amp;checked=false/);
    assert.match(output, /offset=17&amp;checked=true/);
    assert.match(output, /<strong>同名<\/strong>/);
    assert.match(output, /class="katex/);
});

test('nested and empty tasks work, read-only tasks have no action', () => {
    const output = html('> - [ ]\n>   1. [x] child', { tasks: [{ line: 0, offset: 5, checked: false }, { line: 1, offset: 18, checked: true }], editable: false });
    assert.equal((output.match(/role="checkbox"/g) || []).length, 2);
    assert.doesNotMatch(output, /href=/);
    assert.match(output, /aria-disabled="true"/);
});

test('escaped markers, references and code never become task actions', () => {
    const source = '- \\[ ] escaped\n- [x] reference\n\n[x]: https://example.com\n\n```text\n- [ ] code\n```';
    assert.doesNotMatch(html(source, { tasks: [{ line: 0, offset: 4, checked: false }, { line: 1, offset: 20, checked: true }, { line: 6, offset: 40, checked: false }] }), /role="checkbox"/);
});

test('images resolve only through the native manifest and retain open actions', () => {
    const images = [{ source: 'attachments/测试 图片.png', url: 'https://appassets.androidplatform.net/note/revision/image/0', index: 0, local: true }];
    const output = html('![本地](<attachments/测试 图片.png>) ![未授权](https://example.com/image) ![文件](file:///secret)', { images });
    assert.equal((output.match(/<img /g) || []).length, 1);
    assert.match(output, /action\/image\?id=revision&amp;index=0/);
    const linked = html('[![图片](<attachments/测试 图片.png>)](https://example.com)', { images });
    assert.equal((linked.match(/<a /g) || []).length, 1);
    assert.match(linked, /href="https:\/\/example.com"/);
});

test('table, link and Markdown formatting coexist with math', () => {
    const output = html('| 名称 | 数值 |\n| --- | --- |\n| **面积** | $r^2$ |\n\n[链接](https://example.com)');
    assert.match(output, /<table>/);
    assert.match(output, /class="katex/);
    assert.match(output, /<strong>面积<\/strong>/);
    assert.match(output, /href="https:\/\/example.com"/);
});

test('all vendored files match pinned hashes and every KaTeX font is offline', () => {
    for (const line of fs.readFileSync(path.join(assets, 'vendor/SHA256SUMS'), 'utf8').trim().split('\n')) {
        const [expected, name] = line.split('  ');
        assert.equal(createHash('sha256').update(fs.readFileSync(path.join(assets, 'vendor', name))).digest('hex'), expected, name);
    }
    const css = fs.readFileSync(path.join(assets, 'vendor/katex.min.css'), 'utf8');
    for (const [, font] of css.matchAll(/url\(([^)]+)\)/g)) assert.ok(fs.existsSync(path.join(assets, 'vendor', font)), font);
});
