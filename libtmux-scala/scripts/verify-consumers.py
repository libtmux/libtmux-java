#!/usr/bin/env python3
"""Resolve and run isolated consumers of the staged Scala distributions."""

import argparse
import hashlib
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import platform
import posixpath
import re
import shutil
import subprocess
import sys
import time
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET
from zipfile import BadZipFile, ZipFile


ROOT = Path(__file__).resolve().parents[2]
MODULES = tuple(
    f"{name}_3" for name in ("libtmux-scala", "libtmux-scala-cats")
)
SCALAS = ("3.3.8", "3.9.0")
GROUP = "io.github.libtmux"
POM = {"m": "http://maven.apache.org/POM/4.0.0"}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def artifact(repository, module, version, extension="jar"):
    return repository / "io/github/libtmux" / module / version / (
        f"{module}-{version}.{extension}"
    )


class Links(HTMLParser):
    def __init__(self, source):
        super().__init__()
        self.links = []
        self.anchors = set()
        self.feed(source)

    def handle_starttag(self, tag, attributes):
        values = dict(attributes)
        if "href" in values:
            self.links.append(values["href"])
        for name in ("id", "name"):
            if values.get(name):
                self.anchors.add(values[name])


def verify_pom(path, module, version, java_version):
    pom = ET.parse(path).getroot()

    def value(name):
        return pom.findtext("/".join("m:" + part for part in name.split("/")),
                            default="", namespaces=POM)

    for key, expected in (("groupId", GROUP), ("artifactId", module),
                          ("version", version), ("packaging", "jar")):
        if value(key) != expected:
            raise ValueError(f"Wrong POM {key}: {module}")
    homepage = "https://github.com/libtmux/libtmux-java"
    if value("url") != homepage or value("scm/url") != homepage:
        raise ValueError(f"Wrong POM homepage/SCM: {module}")
    if (value("scm/connection") != "scm:git:" + homepage + ".git" or
            not value("scm/developerConnection").endswith("libtmux/libtmux-java.git")):
        raise ValueError(f"Wrong POM SCM connection: {module}")
    if (value("licenses/license/name") not in ("MIT", "MIT License") or
            value("licenses/license/url") != "https://opensource.org/licenses/MIT"):
        raise ValueError(f"Wrong POM license: {module}")
    if not all(value(key) for key in (
            "name", "description", "developers/developer/id",
            "developers/developer/name", "developers/developer/url")):
        raise ValueError(f"Missing POM publication metadata: {module}")
    dependencies = {}
    for dependency in pom.findall("m:dependencies/m:dependency", POM):
        def field(name):
            return dependency.findtext("m:" + name, default="", namespaces=POM)
        if field("scope") in ("test", "provided"):
            continue
        coordinate = (field("groupId"), field("artifactId"))
        if coordinate in dependencies:
            raise ValueError(f"Duplicate POM dependency: {module}")
        dependencies[coordinate] = field("version")
    suffix = module.rsplit("_", 1)[1]
    expected = {("org.scala-lang", "scala3-library_3")}
    if module.startswith("libtmux-scala-cats_"):
        expected.update({(GROUP, "libtmux-scala_" + suffix),
                         ("org.typelevel", "cats-effect_" + suffix),
                         ("co.fs2", "fs2-core_" + suffix)})
        pinned = (GROUP, "libtmux-scala_" + suffix)
        pinned_version = version
    else:
        expected.add((GROUP, "libtmux"))
        pinned = (GROUP, "libtmux")
        pinned_version = java_version
    if set(dependencies) != expected or dependencies.get(pinned) != pinned_version:
        raise ValueError(f"Wrong POM runtime dependencies: {module}: {dependencies}")
    return {":".join(key): version for key, version in dependencies.items()}


def verify_archives(binary, source, documentation):
    with ZipFile(binary) as jar:
        classes = [name for name in jar.namelist() if name.endswith(".class")]
        if not classes:
            raise ValueError("Binary jar contains no classes: " + binary.name)
        for name in classes:
            header = jar.read(name)[:8]
            if header[:4] != b"\xca\xfe\xba\xbe" or int.from_bytes(header[6:8], "big") != 69:
                raise ValueError("Classfile must target JDK 25: " + name)
    with ZipFile(source) as sources, ZipFile(documentation) as docs:
        names = set(docs.namelist())
        if "index.html" not in names:
            raise ValueError("Scaladoc index is missing: " + documentation.name)
        inventory = dict(line.split("\t") for line in
                         docs.read("_sources/sources.tsv").decode().splitlines())
        source_names = {name for name in sources.namelist() if name.endswith(".scala")}
        if not inventory or set(inventory) != source_names:
            raise ValueError("Source jar and Scaladoc source inventories differ")
        pages = {}
        for name, expected in inventory.items():
            raw = sources.read(name)
            if (hashlib.sha256(raw).hexdigest() != expected or
                    docs.read("_sources/" + name) != raw):
                raise ValueError("Scaladoc raw source differs from source jar: " + name)
            page = "_sources/" + name + ".html"
            pages[page] = Links(docs.read(page).decode())
            if "L1" not in pages[page].anchors:
                raise ValueError("Source page has no line anchors: " + page)
        checked = 0
        for name in sorted(names):
            if not name.endswith(".html") or name.startswith("_sources/"):
                continue
            text = docs.read(name).decode()
            if "libtmux-source.invalid" in text or "file:///" in text:
                raise ValueError("Unresolved or local filesystem Scaladoc source URL")
            for link in Links(text).links:
                if "_sources/" not in link:
                    continue
                url = urlsplit(link)
                if url.scheme or url.netloc or url.query:
                    raise ValueError("Scaladoc source URL must be relative: " + link)
                target = posixpath.normpath(posixpath.join(
                    posixpath.dirname(name), unquote(url.path)))
                if target not in pages or unquote(url.fragment) not in pages[target].anchors:
                    raise ValueError("Broken Scaladoc source target/anchor: " + name + ": " + link)
                checked += 1
        if not checked:
            raise ValueError("Scaladoc contains no API source links")
    return {"classes": len(classes), "sources": len(inventory), "source_links": checked}


def artifact_inventory(scala_stage, version, java_version):
    group = scala_stage / "io/github/libtmux"
    published = {directory.name for directory in group.iterdir()
                 if directory.is_dir() and (directory / version).is_dir()
                 and any(path.suffix in (".jar", ".pom")
                         for path in (directory / version).iterdir())}
    if published != set(MODULES):
        raise ValueError("Unexpected/missing staged publications: " + repr(sorted(published ^ set(MODULES))))
    result = {}
    for module in MODULES:
        binary = artifact(scala_stage, module, version)
        sources = binary.with_name(binary.stem + "-sources.jar")
        docs = binary.with_name(binary.stem + "-javadoc.jar")
        pom = artifact(scala_stage, module, version, "pom")
        files = (binary, sources, docs, pom)
        expected = {path.name for path in files}
        actual = {path.name for path in binary.parent.iterdir()
                  if path.suffix in (".jar", ".pom")}
        if actual != expected:
            raise ValueError("Unexpected/missing staged files: " + repr(sorted(actual ^ expected)))
        result[module] = {
            "sha256": {path.name: digest(path) for path in files},
            "dependencies": verify_pom(pom, module, version, java_version),
            **verify_archives(binary, sources, docs),
        }
    return result


def require_stage(scala_stage, java_stage, version, java_version):
    files = [
        artifact(scala_stage, module, version, extension)
        for module in MODULES
        for extension in ("jar", "pom")
    ] + [
        artifact(java_stage, module, java_version, extension)
        for module in ("libtmux", "libtmux-junit5")
        for extension in ("jar", "pom")
    ]
    missing = [str(path) for path in files if not path.is_file()]
    if missing:
        raise ValueError("Missing staged coordinates:\n" + "\n".join(missing))
    return {str(path.relative_to(repository)): digest(path)
            for repository in (scala_stage, java_stage)
            for path in files if path.is_relative_to(repository)}


def build_fences(root):
    ignored = {".git", ".gradle", ".bsp", ".metals", ".idea", "target", "build", "node_modules"}
    found = {}
    directive = re.compile(r"^<!--\s*snippet:\s*scala-build:\s*([a-z0-9][a-z0-9-]*)(?:\s*\|.*?)?\s*-->$")
    for directory, folders, files in os.walk(root):
        folders[:] = [name for name in folders if name not in ignored]
        for filename in files:
            if not filename.endswith(".md"):
                continue
            path = Path(directory) / filename
            fence = None
            for line in path.read_text().splitlines():
                opening = re.match(r"^ {0,3}(`{3,}|~{3,})", line)
                if fence:
                    if re.fullmatch(r" {0,3}" + re.escape(fence[0]) +
                                    "{" + str(len(fence)) + r",}\s*", line):
                        fence = None
                    continue
                if opening:
                    fence = opening.group(1)
                    continue
                match = directive.fullmatch(line)
                if match:
                    name = match.group(1)
                    if name in found:
                        raise ValueError("Duplicate documentation build fence: " + name)
                    found[name] = str(path.relative_to(root))
    return found


def exported_settings(directory, version):
    inventory = directory / "scala-docs-inventory.tsv"
    hashes = {}
    for line in inventory.read_text().splitlines():
        document, opening, name, mode, sha = line.split("\t")
        if mode == "build":
            lines = (ROOT / document).read_text().splitlines()
            start = int(opening) - 1
            if lines[start].strip() != "```sbt":
                raise ValueError("Stale documentation fence location: " + name)
            end = start + 1
            while end < len(lines) and lines[end].strip() != "```":
                end += 1
            code = "\n".join(lines[start + 1:end]) + "\n"
            if end == len(lines) or hashlib.sha256(code.encode()).hexdigest() != sha:
                raise ValueError("Stale documentation build export: " + name)
            hashes[name] = sha
    discovered = set(build_fences(ROOT))
    if discovered != set(hashes):
        raise ValueError("Documentation inventory omits/adds build fences: " +
                         repr(sorted(discovered ^ set(hashes))))
    expected = {"install-staged-core", "install-staged-cats", "install-direct-java"}
    if set(hashes) != expected:
        raise ValueError("Unhandled or missing documentation build fences: " +
                         repr(sorted(set(hashes) ^ expected)))
    result = {}
    for name in sorted(expected):
        path = directory / "scala-doc-build" / (name + ".sbt")
        if digest(path) != hashes.get(name):
            raise ValueError(f"Documentation export hash differs: {name}")
        if name != "install-direct-java" and '"' + version + '"' not in path.read_text():
            raise ValueError(f"Documentation export pins another version: {name}")
        result[name] = path
    return result


def prepare(directory, tool, exports):
    template = ROOT / "libtmux-scala/consumers"
    shutil.copytree(template / tool, directory, dirs_exist_ok=True)
    source_hashes = {}
    fixture = ROOT / (
        "libtmux-scala/integration/src/test/scala/io/github/libtmux/scaladsl/fixture/OwnedTmux.scala"
    )
    for kind in (("core", "cats", "direct") if tool == "sbt" else ("core", "cats")):
        project = directory / kind
        sources = project / "src/test/scala"
        sources.mkdir(parents=True, exist_ok=True)
        application = template / (kind.capitalize() + "Consumer.scala")
        for path in (application, fixture):
            target = sources / path.name
            shutil.copyfile(path, target)
            source_hashes[str(path.relative_to(ROOT))] = digest(target)
        if tool == "sbt":
            selected = "install-direct-java" if kind == "direct" else "install-staged-core"
            shutil.copyfile(exports[selected], project / "10-install.sbt")
            if kind == "cats":
                shutil.copyfile(exports["install-staged-cats"], project / "20-cats.sbt")
    if tool == "sbt":
        (directory / "project").mkdir(exist_ok=True)
        shutil.copyfile(ROOT / "project/build.properties",
                        directory / "project/build.properties")
    return source_hashes


def runtime_inventory(path, kind, suffix, args):
    result = []
    expected = {"libtmux"} if kind == "direct" else {"libtmux", "libtmux-scala_" + suffix}
    if kind == "cats":
        expected.add("libtmux-scala-cats_" + suffix)
    actual = set()
    for line in path.read_text().splitlines():
        group, module, version, filename = line.split("\t")
        resolved = Path(filename).resolve()
        if not resolved.is_file() or resolved.suffix != ".jar":
            raise ValueError(f"Runtime dependency is not an installed jar: {filename}")
        if resolved.is_relative_to(Path.home() / ".m2"):
            raise ValueError(f"Maven-local dependency: {module}")
        family = re.search(r"_(2\.\d+|3)$", module)
        if family and family.group(1) != suffix:
            raise ValueError(f"Mixed Scala runtime families: {module}")
        standard = group == "org.scala-lang" and module in (
            "scala-library", "scala3-library_3")
        # The unsuffixed `scala-library` is Scala 3's own transitive Scala 2.13
        # standard library dependency, not a leftover cross-build artifact.
        compatible_standard = version.startswith("2.13.") or (
            version == args.scala_version and version.startswith("3."))
        if module == "scala-library" and not compatible_standard:
            raise ValueError("Wrong Scala standard library: " + version)
        if kind != "cats" and group != GROUP and not standard:
            raise ValueError(f"Optional runtime dependency leaked into core: {group}:{module}")
        sha = digest(resolved)
        if group == GROUP:
            actual.add(module)
            if module not in expected:
                raise ValueError(f"Unexpected runtime libtmux module: {module}")
            stage = args.java_stage if module == "libtmux" else args.scala_stage
            pinned = (args.direct_java_version if kind == "direct" else
                      args.java_version if module == "libtmux" else args.version)
            if version != pinned:
                raise ValueError(f"Wrong dependency version: {module}:{version}")
            if sha != digest(artifact(stage, module, pinned)):
                raise ValueError(f"Resolved jar differs from stage: {module}")
        result.append({"coordinate": f"{group}:{module}:{version}", "sha256": sha})
    if actual != expected:
        raise ValueError(f"Wrong {kind} runtime libtmux modules: {sorted(actual)}")
    if kind == "cats":
        names = {item["coordinate"].split(":")[1] for item in result}
        if not {"cats-effect_" + suffix, "fs2-core_" + suffix}.issubset(names):
            raise ValueError("Cats/FS2 runtime dependencies are missing")
    if not result:
        raise ValueError("Empty runtime artifact inventory")
    return result


def verify_test_runtime(path, runtime):
    selected = {}
    for line in path.read_text().splitlines():
        group, module, version, filename = line.split("\t")
        key = group + ":" + module
        if key in selected:
            raise ValueError("Duplicate execution dependency: " + key)
        selected[key] = {"coordinate": key + ":" + version, "sha256": digest(Path(filename))}
    for expected in runtime:
        key = expected["coordinate"].rsplit(":", 1)[0]
        if selected.get(key) != expected:
            raise ValueError("Test fixture changed the runtime dependency under test: " + key)


def run(command, directory, environment, log, timeout):
    started = time.monotonic()
    with log.open("w") as output:
        completed = subprocess.run(command, cwd=directory, env=environment,
                                   stdout=output, stderr=subprocess.STDOUT,
                                   timeout=timeout)
    elapsed = time.monotonic() - started
    print(f"{directory.name}: exit={completed.returncode} elapsed={elapsed:.3f}s log={log}",
          flush=True)
    return {"command": command, "exit": completed.returncode,
            "elapsed_seconds": elapsed, "log": str(log)}


def commands(tool, directory, args):
    if tool == "gradle":
        result = [str(ROOT / "gradlew"), "--project-dir", str(directory),
                  "--console=plain", "--no-configuration-cache", "--max-workers=2"]
        if args.offline:
            result.append("--offline")
        return result + ["core:writeRuntime", "cats:writeRuntime",
                         "core:verifyConsumer", "cats:verifyConsumer"]
    version = (ROOT / "project/build.properties").read_text().strip().split("=")[1]
    cache = Path(os.environ.get("LIBTMUX_SBT_CACHE", str(
        Path(os.environ.get("XDG_CACHE_HOME", str(Path.home() / ".cache"))) / "libtmux-sbt"
    )))
    launcher = cache / f"sbt-launch-{version}.jar"
    if digest(launcher) != (ROOT / "project/sbt-launch.sha256").read_text().strip():
        raise ValueError("Cached sbt launcher checksum mismatch")
    repositories = directory / "repositories"
    repositories.write_text(
        "[repositories]\njava-stage: " + args.java_stage.as_uri() +
        "\nscala-stage: " + args.scala_stage.as_uri() +
        "\ncentral: https://repo.maven.apache.org/maven2\n"
    )
    result = [str(args.jdk / "bin/java"), "-Xms256m", "-Xmx2g",
              "-XX:ActiveProcessorCount=4", "-Dsbt.supershell=false",
              "-Dsbt.log.noformat=true", "-Dsbt.override.build.repos=true",
              "-Dsbt.global.base=" + str(args.output / "sbt-global"),
              "-Dsbt.repository.config=" + str(repositories),
              "-jar", str(launcher)]
    if args.offline:
        result.append("set ThisBuild / offline := true")
    return result + ["core/writeRuntime", "cats/writeRuntime", "direct/writeRuntime",
                     "core/Test/runMain io.github.libtmux.scaladsl.consumer.CoreConsumer",
                     "cats/Test/runMain io.github.libtmux.scaladsl.consumer.CatsConsumer",
                     "direct/Test/runMain io.github.libtmux.scaladsl.consumer.DirectConsumer"]


def verify(args, staged):
    started = args.started
    for key in ("jdk", "tmux", "scala_version", "output", "docs_exports"):
        if getattr(args, key) is None:
            raise ValueError("Missing required run argument: --" + key.replace("_", "-"))
    for key in ("jdk", "tmux", "output", "docs_exports"):
        setattr(args, key, getattr(args, key).resolve())
    if args.output == ROOT or args.output == ROOT / "libtmux-scala":
        raise ValueError("Consumer output must be a separate build directory")
    java = subprocess.check_output([str(args.jdk / "bin/java"), "-version"],
                                   stderr=subprocess.STDOUT, text=True)
    matched = re.search(r'version "(25|27)[.\"]', java)
    if not matched:
        raise ValueError("Consumer JDK must be 25 or 27: " + java.strip())
    jdk = matched.group(1)
    tmux = subprocess.check_output([str(args.tmux), "-V"], text=True).strip()
    if tmux != "tmux " + args.expected_tmux:
        raise ValueError("Unexpected tmux version: " + tmux)
    exports = exported_settings(args.docs_exports, args.version)
    pinned = re.search(r'"io.github.libtmux"\s*%\s*"libtmux"\s*%\s*"([^\"]+)"',
                       exports["install-direct-java"].read_text())
    if not pinned:
        raise ValueError("Direct Java installation must pin its coordinate")
    args.direct_java_version = pinned.group(1)
    if args.direct_java_version != args.java_version:
        raise ValueError(
            "Direct Java installation must match the staged Java version"
        )
    os_name = {"Linux": "linux", "Darwin": "macos"}.get(platform.system())
    if os_name is None:
        raise ValueError("Consumer matrix requires Linux or macOS")
    args.output.mkdir(parents=True, exist_ok=True)
    if (args.output / "consumer-evidence.json").exists():
        archive = args.output / "attempts" / str(time.time_ns())
        archive.mkdir(parents=True)
        for path in (args.output / "consumer-evidence.json", *args.output.glob("*.log")):
            shutil.copyfile(path, archive / path.name)
    evidence = {"status": "RUNNING", "java": java.strip(), "tmux": tmux,
                "staged_sha256": staged,
                "artifacts": args.artifacts,
                "documentation_sha256": {name: digest(path) for name, path in exports.items()},
                "build_fences": {name: "UNVERIFIED" for name in exports},
                "matrix": {
                    f"{system}/jdk{level}/scala{scala}/{tool}": "UNVERIFIED"
                    for system in ("linux", "macos") for level in (25, 27)
                    for scala in SCALAS for tool in ("sbt", "gradle")
                }, "runs": {}}
    report = args.output / "consumer-evidence.json"
    environment = os.environ.copy()
    for key in ("TMUX", "TMUX_PANE", "CLASSPATH"):
        environment.pop(key, None)
    environment.update({"JAVA_HOME": str(args.jdk),
                        "PATH": str(args.jdk / "bin") + os.pathsep + environment["PATH"],
                        "CONSUMER_SCALA_VERSION": args.scala_version,
                        "LIBTMUX_SCALA_VERSION": args.version,
                        "LIBTMUX_JAVA_VERSION": args.java_version,
                        "CONSUMER_DIRECT_JAVA_VERSION": args.direct_java_version,
                        "LIBTMUX_SCALA_STAGING": str(args.scala_stage),
                        "LIBTMUX_JAVA_REPOSITORY": str(args.java_stage),
                        "TMUX_TEST_BINARY": str(args.tmux)})
    try:
        for tool in ("sbt", "gradle") if args.tool == "both" else (args.tool,):
            cell = f"{os_name}/jdk{jdk}/scala{args.scala_version}/{tool}"
            directory = args.output / tool
            sources = prepare(directory, tool, exports)
            invocation = commands(tool, directory, args)
            if args.prepare_only:
                evidence["runs"][tool] = {"status": "PREPARED", "command": invocation,
                                          "source_sha256": sources}
                continue
            remaining = 300 - (time.monotonic() - started)
            if remaining <= 0:
                raise ValueError("Consumer command exceeded its five-minute budget")
            result = run(invocation, directory, environment,
                         args.output / (tool + ".log"), remaining)
            result["source_sha256"] = sources
            evidence["runs"][tool] = result
            evidence["matrix"][cell] = "FAIL"
            if result["exit"]:
                raise ValueError(tool + " consumer failed; inspect " + result["log"])
            log = Path(result["log"]).read_text()
            suffix = "3"
            result["runtime"] = {}
            result["compiler_jars"] = {}
            if tool == "sbt":
                expected_tool = (ROOT / "project/build.properties").read_text().strip().split("=")[1]
            else:
                wrapper = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text()
                expected_tool = re.search(r"gradle-([0-9.]+)-bin.zip", wrapper).group(1)
            for kind in (("core", "cats", "direct") if tool == "sbt" else ("core", "cats")):
                marker = f"CONSUMER_PASS {kind} cleanup=true java={jdk}"
                if marker not in log:
                    raise ValueError("Missing executed consumer/cleanup witness: " + kind)
                if (directory / kind / "build-tool.txt").read_text().strip() != expected_tool:
                    raise ValueError("Consumer did not use the pinned build tool")
                result["build_tool_version"] = expected_tool
                compiler = (directory / kind / "compiler.txt").read_text().splitlines()
                if f"scala3-compiler_3-{args.scala_version}.jar" not in compiler:
                    raise ValueError("Wrong compiler jars: " + repr(compiler))
                result["compiler_jars"][kind] = compiler
                result["runtime"][kind] = runtime_inventory(
                    directory / kind / "runtime.tsv", kind, suffix, args)
                verify_test_runtime(directory / kind / "test.tsv", result["runtime"][kind])
            result["input_sha256"] = {
                str(path.relative_to(directory)): digest(path)
                for path in directory.rglob("*")
                if path.is_file() and not any(part in ("target", "build", ".gradle")
                                             for part in path.relative_to(directory).parts)
            }
            evidence["matrix"][cell] = "PASS"
            if tool == "sbt":
                evidence["build_fences"] = {name: "PASS" for name in exports}
        if staged != require_stage(args.scala_stage, args.java_stage,
                                   args.version, args.java_version):
            raise ValueError("Staged coordinates changed during consumer execution")
        if args.artifacts != artifact_inventory(args.scala_stage, args.version, args.java_version):
            raise ValueError("Staged publication files changed during consumer execution")
        evidence["status"] = "PREPARED" if args.prepare_only else "PASS"
    except Exception:
        evidence["status"] = "FAIL"
        raise
    finally:
        evidence["elapsed_seconds"] = time.monotonic() - started
        report.write_text(json.dumps(evidence, indent=2) + "\n")
        print("Evidence: " + str(report), flush=True)


def main():
    started = time.monotonic()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scala-stage", type=Path, required=True)
    parser.add_argument("--java-stage", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--java-version", required=True)
    parser.add_argument("--preflight", action="store_true")
    parser.add_argument("--artifacts-only", action="store_true")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--jdk", type=Path)
    parser.add_argument("--tmux", type=Path)
    parser.add_argument("--expected-tmux", default="3.7c")
    parser.add_argument("--scala-version", choices=SCALAS)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--docs-exports", type=Path)
    parser.add_argument("--tool", choices=("both", "sbt", "gradle"), default="both")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    args.started = started
    args.scala_stage = args.scala_stage.resolve()
    args.java_stage = args.java_stage.resolve()
    staged = require_stage(args.scala_stage, args.java_stage,
                           args.version, args.java_version)
    args.artifacts = artifact_inventory(args.scala_stage, args.version, args.java_version)
    if args.artifacts_only:
        print(json.dumps(args.artifacts, indent=2))
    elif args.preflight:
        print("Stage preflight passed")
    else:
        verify(args, staged)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, BadZipFile, ET.ParseError,
            subprocess.SubprocessError) as failure:
        print(f"FAIL: {failure}", file=sys.stderr)
        sys.exit(1)
