#!/usr/bin/env python3
"""Run JVM tests and Android source checks on Termux. Never assemble/package an APK."""

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[1]
WORK = Path.home() / "tmp" / "colormemo-validation"
REPOSITORY = "https://dl.google.com/android/repository/"


def download(url, target, checksum=None, algorithm="sha256"):
    if target.exists() and checksum:
        if hashlib.new(algorithm, target.read_bytes()).hexdigest() == checksum:
            return
    print(f"Downloading {target.name}", flush=True)
    part = target.with_suffix(target.suffix + ".part")
    with urllib.request.urlopen(url, timeout=90) as response, part.open("wb") as output:
        shutil.copyfileobj(response, output)
    if checksum and hashlib.new(algorithm, part.read_bytes()).hexdigest() != checksum:
        raise RuntimeError(f"Checksum failed: {target.name}")
    part.replace(target)


def local_name(tag):
    return tag.rsplit("}", 1)[-1]


def find(element, name):
    return next((child for child in element if local_name(child.tag) == name), None)


def prepare_sdk():
    sdk = WORK / "sdk"
    packages = {"platforms;android-35": sdk / "platforms/android-35", "build-tools;35.0.0": sdk / "build-tools/35.0.0"}
    if all((path / "source.properties").exists() for path in packages.values()):
        return sdk
    print("Reading official Android SDK package metadata", flush=True)
    with urllib.request.urlopen(REPOSITORY + "repository2-1.xml", timeout=60) as response:
        metadata = ET.fromstring(response.read())
    downloads = WORK / "downloads"
    downloads.mkdir(parents=True, exist_ok=True)
    for package_id, target in packages.items():
        if (target / "source.properties").exists():
            continue
        matches = [item for item in metadata if local_name(item.tag) == "remotePackage" and item.attrib.get("path") == package_id]
        # The legacy repository lists extension SDK previews with the same path.
        # Prefer the ordinary platform; never relabel an extension SDK as API 35.
        if package_id.startswith("platforms;"):
            matches = [item for item in matches if "-ext" not in find(item, "display-name").text]
        package = max(matches, key=lambda item: tuple(int(part.text) for part in find(item, "revision")))
        archives = find(package, "archives")
        archive = next(item for item in archives if find(item, "host-os") is None or find(item, "host-os").text == "linux")
        complete = find(archive, "complete")
        relative_url = find(complete, "url").text
        digest = find(complete, "checksum")
        algorithm = digest.attrib.get("type", "sha1")
        archive_path = downloads / Path(relative_url).name
        download(REPOSITORY + relative_url, archive_path, digest.text.strip(), algorithm)
        extracted = WORK / "sdk-extracted" / target.name
        extracted.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(archive_path) as zipped:
            for item in zipped.infolist():
                output = (extracted / item.filename).resolve()
                if not output.is_relative_to(extracted.resolve()):
                    raise RuntimeError("Invalid path in SDK archive")
            zipped.extractall(extracted)
        source_properties = list(extracted.glob("*/source.properties"))
        if len(source_properties) != 1:
            raise RuntimeError(f"Unexpected SDK package layout: {package_id}")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(source_properties[0].parent), str(target))
        print(f"Installed {package_id}", flush=True)
    return sdk


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core-only", action="store_true", help="Run only protocol JVM tests; no Android SDK required")
    parser.add_argument("--rerun-tasks", action="store_true", help="Refresh generated outputs after resource moves")
    parser.add_argument("--aapt2", type=Path, help="Termux-compatible aapt2 executable for source/resource checks")
    args = parser.parse_args()
    WORK.mkdir(parents=True, exist_ok=True)
    for directory in ["tmp", "android-user-home", "gradle"]:
        (WORK / directory).mkdir(exist_ok=True)
    env = os.environ.copy()
    termux_lib = Path("/data/data/com.termux/files/usr/lib")
    if termux_lib.is_dir():
        inherited_libraries = env.get("LD_LIBRARY_PATH", "")
        env["LD_LIBRARY_PATH"] = os.pathsep.join(
            [str(termux_lib), *(part for part in inherited_libraries.split(os.pathsep) if part)]
        )
    env.update({
        "GRADLE_USER_HOME": str(WORK / "gradle"),
        "ANDROID_USER_HOME": str(WORK / "android-user-home"),
        "TMPDIR": str(WORK / "tmp"),
        "GRADLE_OPTS": "-Dorg.gradle.native=false -Dorg.gradle.vfs.watch=false -Dorg.gradle.internal.instrumentation.agent=false " + f"-Djava.io.tmpdir={WORK / 'tmp'}",
    })
    command = ["sh", str(ROOT / "gradlew"), "--no-daemon", "--max-workers=2", "--console=plain",
               "-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -Dorg.gradle.native=false -Dorg.gradle.vfs.watch=false -Dorg.gradle.internal.instrumentation.agent=false " + f"-Djava.io.tmpdir={WORK / 'tmp'}",
               "-Pkotlin.compiler.execution.strategy=in-process"]
    if args.rerun_tasks:
        command.append("--rerun-tasks")
    if args.core_only:
        command += ["-PcoreOnly=true", ":core:test"]
        log = WORK / "core-tests.log"
    else:
        subprocess.run(["node", "--test", "tests/markdown-preview.test.cjs"], cwd=ROOT, env=env, check=True)
        sdk = prepare_sdk()
        env.update({"ANDROID_HOME": str(sdk), "ANDROID_SDK_ROOT": str(sdk)})
        candidates = [args.aapt2, Path(shutil.which("aapt2") or "/nonexistent"),
                      Path.home() / "tmp/materialfiles-apk-tools/toolchain/bin/aapt2"]
        aapt2 = next((path for path in candidates if path and path.is_file()), None)
        if aapt2 is None:
            raise RuntimeError("Install a Termux-compatible aapt2 and pass --aapt2 PATH; no APK will be assembled")
        command += [f"-Pandroid.aapt2FromMavenOverride={aapt2}", "-Pandroid.builder.sdkDownload=false",
                    ":core:test", ":app:compileDebugKotlin", ":app:testDebugUnitTest", ":app:lintDebug"]
        log = WORK / "android-checks.log"
    print(f"Running source checks (no APK tasks); log: {log}", flush=True)
    with log.open("w") as output:
        result = subprocess.run(command, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT)
    lines = log.read_text(errors="replace").splitlines()
    print("\n".join(lines[-80 if result.returncode else -25:]))
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
