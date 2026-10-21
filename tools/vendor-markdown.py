#!/usr/bin/env python3
"""Refresh pinned, offline Markdown renderer assets (does not build an APK)."""

import base64
import hashlib
from pathlib import Path
import tarfile
import urllib.request


ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "app/src/main/assets/markdown/vendor"
WORK = Path.home() / "tmp/colormemo-markdown"

# npm registry SHA-512 integrity values; never resolve floating versions at build time.
PACKAGES = {
    "mermaid": ("11.16.0", "Zvm3kbstgdpvIJPPItlL7fppIZ3kibvc1oZIGxdvk9t6UFz6flv+Jw7FtRGKwfcI8OckmH04LqG6LlS6X4B1pA==",
                ["dist/mermaid.min.js", "LICENSE"]),
    "katex": ("0.19.0", "v6Tznz3zJ7u3niRCoDTsumM2+HA2XXcCu+WAacCeHD2z3p9A9Ks987o5FzfTGBN0e8A0vjEgIDvLbICrXpdw/Q==",
              ["dist/katex.min.js", "dist/katex.min.css", "LICENSE"]),
    "markdown-it": ("15.0.3", "+Az7Ojz3iRGu8KYMvJ6xVhvrxP3eE+vEtJX75Ow88XBpcQNV/0lGX5ZynIIBftREo4DC2jsveAMDaeltmm8QvQ==",
                    ["dist/browser/markdown-it.umd.min.js", "LICENSE"]),
    "markdown-it-texmath": ("1.0.0", "4hhkiX8/gus+6e53PLCUmUrsa6ZWGgJW2XCW6O0ASvZUiezIK900ZicinTDtG3kAO2kon7oUA/ReWmpW2FByxg==",
                          ["texmath.js", "license.txt"]),
}


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    DEST.mkdir(parents=True, exist_ok=True)
    hashes = []
    for name, (version, digest, files) in PACKAGES.items():
        archive = WORK / f"{name}-{version}.tgz"
        if not archive.exists():
            urllib.request.urlretrieve(f"https://registry.npmjs.org/{name}/-/{name}-{version}.tgz", archive)
        actual = base64.b64encode(hashlib.sha512(archive.read_bytes()).digest()).decode()
        if actual != digest:
            raise RuntimeError(f"Integrity mismatch: {archive}")
        with tarfile.open(archive) as package:
            names = ["package/" + path for path in files]
            if name == "katex":
                names += [m.name for m in package.getmembers()
                          if m.name.startswith("package/dist/fonts/") and m.isfile()]
            for path in names:
                relative = path.removeprefix("package/").removeprefix("dist/")
                if name == "markdown-it" and relative.endswith(".js"):
                    relative = "markdown-it.min.js"
                if "license" in relative.lower():
                    relative = name + "-LICENSE.txt"
                output = DEST / relative
                output.parent.mkdir(parents=True, exist_ok=True)
                data = package.extractfile(path).read()
                output.write_bytes(data)
                hashes.append(f"{hashlib.sha256(data).hexdigest()}  {relative}")
        print(f"Vendored {name} {version}")
    (DEST / "SHA256SUMS").write_text("\n".join(sorted(hashes)) + "\n")


if __name__ == "__main__":
    main()
