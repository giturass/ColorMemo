/* Offline preview. Note text is always parsed as Markdown with raw HTML disabled. */
(function (root, factory) {
    if (typeof module === 'object' && module.exports) {
        module.exports = factory(require('./vendor/markdown-it.min.js'), require('./vendor/katex.min.js'), require('./vendor/texmath.js'));
    } else {
        root.ColorMemoMarkdown = factory(root.markdownit, root.katex, root.texmath);
    }
})(typeof window === 'object' ? window : globalThis, function (markdownit, katex, texmath) {
    'use strict';
    const md = markdownit({ html: false, linkify: false, typographer: false });
    const escape = md.utils.escapeHtml;
    const mathOptions = { trust: false, throwOnError: false, strict: 'ignore', maxExpand: 1000, maxSize: 20 };
    md.use(texmath, { engine: katex, delimiters: ['dollars', 'brackets'], katexOptions: mathOptions });

    // Preserve the native preview's <u> extension without allowing attributes or other HTML.
    md.inline.ruler.before('html_inline', 'underline', function (state, silent) {
        const match = /^<\/?u>/i.exec(state.src.slice(state.pos));
        if (!match) return false;
        if (!silent) {
            const token = state.push('html_inline', '', 0);
            token.content = match[0].toLowerCase();
        }
        state.pos += match[0].length;
        return true;
    });

    const fence = md.renderer.rules.fence;
    md.renderer.rules.fence = function (tokens, index, options, env, renderer) {
        const token = tokens[index];
        const language = token.info.trim().split(/\s+/)[0].toLowerCase();
        if (language === 'mermaid') {
            // textContent is read only after parsing; diagram source never becomes markup.
            return '<div class="diagram" role="img" aria-label="Mermaid 图表"><pre class="mermaid-source">' + escape(token.content) + '</pre></div>\n';
        }
        if (language === 'math' || language === 'latex') {
            return '<div class="math-block">' + katex.renderToString(token.content, { ...mathOptions, displayMode: true }) + '</div>\n';
        }
        return fence(tokens, index, options, env, renderer);
    };

    function action(kind, env, params) {
        return 'https://appassets.androidplatform.net/action/' + kind + '?id=' + encodeURIComponent(env.id) + params;
    }

    md.core.ruler.after('inline', 'native_tasks', function (state) {
        const byLine = new Map((state.env.tasks || []).map(task => [task.line, task]));
        for (let i = 2; i < state.tokens.length; i++) {
            const token = state.tokens[i];
            if (token.type !== 'inline' || !token.map || state.tokens[i - 1].type !== 'paragraph_open' || state.tokens[i - 2].type !== 'list_item_open') continue;
            const task = byLine.get(token.map[0]);
            const first = token.children[0];
            // Native source positions are authoritative, including escaped markers and references.
            if (!task || !/^\[[ xX]\](?:\s|$)/.test(token.content) || !first || first.type !== 'text' || !/^\[[ xX]\]/.test(first.content)) continue;
            first.content = first.content.slice(3).replace(/^[ \t]+/, '');
            state.tokens[i - 2].attrJoin('class', 'task-item');
            const label = token.content.slice(3).trim() || '待办事项';
            const input = '<input type="checkbox" tabindex="-1" aria-hidden="true"' + (task.checked ? ' checked' : '') + ' disabled>';
            const checked = task.checked ? 'true' : 'false';
            const checkbox = new state.Token('html_inline', '', 0);
            const attrs = ' class="task-toggle" role="checkbox" aria-checked="' + checked + '" aria-label="' + escape(label) + '"';
            checkbox.content = state.env.editable
                ? '<a' + attrs + ' href="' + escape(action('task', state.env, '&offset=' + task.offset + '&checked=' + checked)) + '">' + input + '</a>'
                : '<span' + attrs + ' aria-disabled="true">' + input + '</span>';
            token.children.unshift(checkbox);
        }
    });

    md.renderer.rules.image = function (tokens, index, options, env, renderer) {
        const token = tokens[index];
        const src = token.attrGet('src');
        // markdown-it normalizes spaces and non-ASCII characters in image destinations.
        const image = (env.images || []).find(item => md.normalizeLink(item.source) === src);
        const alt = escape(renderer.renderInlineAsText(token.children, options, env));
        if (!image) return '<span>' + alt + '</span>';
        const img = '<img src="' + escape(image.url) + '" alt="' + alt + '">';
        let linkDepth = 0;
        for (let i = 0; i < index; i++) {
            if (tokens[i].type === 'link_open') linkDepth++;
            if (tokens[i].type === 'link_close') linkDepth--;
        }
        return image.local && linkDepth === 0 ? '<a class="image-action" href="' + escape(action('image', env, '&index=' + image.index)) + '">' + img + '</a>' : img;
    };

    function toHtml(payload) { return md.render(payload.markdown, payload); }

    let current = null;
    let sequence = 0;
    let pending = null;
    let drawing = false;
    let observed = false;

    function reportSize() {
        if (!current || typeof document === 'undefined') return;
        const height = Math.ceil(document.getElementById('content').getBoundingClientRect().height);
        if (window.ColorMemoSize) window.ColorMemoSize.resize(current.id, height);
    }

    async function drawDiagrams() {
        if (drawing) return;
        drawing = true;
        try {
            while (pending) {
                const job = pending;
                pending = null;
                if (!job.elements.length) continue;
                const mermaid = window.mermaid;
                if (!mermaid) {
                    for (const element of job.elements) {
                        const message = document.createElement('p');
                        message.className = 'render-error';
                        message.textContent = '无法加载图表组件，请更新 Android System WebView 后重试。';
                        element.prepend(message);
                    }
                    reportSize();
                    continue;
                }
                mermaid.initialize({
                    startOnLoad: false, securityLevel: 'strict', theme: job.payload.dark ? 'dark' : 'default',
                    fontFamily: 'sans-serif', themeVariables: { fontSize: (16 * job.payload.fontScale) + 'px' },
                    suppressErrorRendering: true,
                    maxTextSize: 50000, maxEdges: 500,
                    // Per-diagram directives cannot relax safety or change the app theme.
                    secure: ['secure', 'securityLevel', 'startOnLoad', 'maxTextSize', 'maxEdges', 'suppressErrorRendering', 'theme'],
                });
                for (const element of job.elements) {
                    if (job.sequence !== sequence) break;
                    const source = element.textContent;
                    const staging = document.createElement('div');
                    staging.style.cssText = 'position:absolute;left:-100000px;top:0;visibility:hidden';
                    document.body.appendChild(staging);
                    try {
                        const result = await mermaid.render('diagram-' + job.sequence + '-' + job.elements.indexOf(element), source, staging);
                        if (job.sequence !== sequence) break;
                        element.innerHTML = result.svg;
                        const svg = element.querySelector('svg');
                        const width = svg && svg.viewBox.baseVal.width;
                        if (width > 0) {
                            // Keep wide diagrams readable; the surrounding element scrolls horizontally.
                            svg.style.maxWidth = 'none';
                            svg.style.width = width + 'px';
                        }
                    } catch (_) {
                        if (job.sequence !== sequence) break;
                        const message = document.createElement('p');
                        message.className = 'render-error';
                        message.textContent = 'Mermaid 图表无法渲染，请检查语法。';
                        element.prepend(message);
                        element.removeAttribute('role');
                    } finally {
                        staging.remove();
                        reportSize();
                    }
                }
            }
        } finally { drawing = false; }
    }

    function render(payload) {
        current = payload;
        sequence++;
        const content = document.getElementById('content');
        document.documentElement.dataset.dark = String(payload.dark);
        document.documentElement.style.colorScheme = payload.dark ? 'dark' : 'light';
        document.documentElement.style.setProperty('--link', payload.linkColor);
        document.body.style.color = payload.foreground;
        document.body.style.fontSize = (16 * payload.fontScale) + 'px';
        try { content.innerHTML = toHtml(payload); }
        catch (_) { content.textContent = payload.markdown; }
        pending = { payload, sequence, elements: Array.from(content.querySelectorAll('.diagram')) };
        if (!observed) {
            observed = true;
            if (typeof ResizeObserver !== 'undefined') new ResizeObserver(reportSize).observe(content);
            window.addEventListener('resize', reportSize);
            content.addEventListener('load', reportSize, true);
            content.addEventListener('error', reportSize, true);
        }
        reportSize();
        if (document.fonts) document.fonts.ready.then(reportSize);
        drawDiagrams();
    }

    return { render, toHtml };
});
