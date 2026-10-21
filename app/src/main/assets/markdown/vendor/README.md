# Offline Markdown dependencies

| Component | Version | Upstream | License |
| --- | --- | --- | --- |
| Mermaid | 11.16.0 | https://github.com/mermaid-js/mermaid | MIT |
| KaTeX, including fonts | 0.19.0 | https://github.com/KaTeX/KaTeX | MIT |
| markdown-it | 15.0.3 | https://github.com/markdown-it/markdown-it | MIT |
| markdown-it-texmath | 1.0.0 | https://github.com/goessner/markdown-it-texmath | MIT |

These are unmodified files from pinned npm release archives, with original licenses alongside them. Mermaid's bundled license notices are retained inside `mermaid.min.js`. `markdown-it.umd.min.js` is renamed to `markdown-it.min.js` for the page's stable asset URL.

Run `python3 tools/vendor-markdown.py` from the repository root to refresh. The script verifies each archive's pinned SHA-512 integrity before extracting only the listed assets, and generates `SHA256SUMS` for runtime assets and licenses. All fonts and scripts are local; Android builds do not run npm or download these files.
