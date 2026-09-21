#!/usr/bin/env python3
"""Compare the staged Scala POM set with the published BOM POM."""

import argparse
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
GROUP = "io.github.libtmux"
GROUP_PATH = Path("io/github/libtmux")
POM = {"m": "http://maven.apache.org/POM/4.0.0"}


def declared(manifest):
    rows = [line for line in manifest.read_text().splitlines() if line]
    pattern = re.compile(r"io\.github\.libtmux:([A-Za-z0-9_.-]+)")
    modules = []
    for row in rows:
        matched = pattern.fullmatch(row)
        if not matched:
            raise ValueError("Invalid Scala publication declaration: " + row)
        modules.append(matched.group(1))
    if not modules or len(modules) != len(set(modules)):
        raise ValueError("Scala publication declarations must be unique and nonempty")
    return tuple(sorted(modules))


def coordinate(node):
    return ":".join(node.findtext("m:" + field, default="", namespaces=POM)
                    for field in ("groupId", "artifactId", "version"))


def staged_poms(stage, modules, version):
    result = set()
    for module in modules:
        path = stage / GROUP_PATH / module / version / (module + "-" + version + ".pom")
        if not path.is_file():
            raise ValueError("Missing staged Scala POM: " + module)
        value = coordinate(ET.parse(path).getroot())
        expected = GROUP + ":" + module + ":" + version
        if value != expected:
            raise ValueError("Wrong staged Scala POM coordinate: " + value)
        result.add(value)
    return result


def bom_scala_constraints(path):
    root = ET.parse(path).getroot()
    values = {
        coordinate(node)
        for node in root.findall("m:dependencyManagement/m:dependencies/m:dependency", POM)
        if node.findtext("m:groupId", default="", namespaces=POM) == GROUP and
        node.findtext("m:artifactId", default="", namespaces=POM).startswith("libtmux-scala")
    }
    if not values:
        raise ValueError("Published BOM has no Scala constraints")
    return values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bom-pom", type=Path, required=True)
    parser.add_argument("--scala-stage", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--manifest", type=Path, default=ROOT / "scala/publications.txt")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        modules = declared(args.manifest.resolve())
        staged = staged_poms(args.scala_stage.resolve(), modules, args.version)
        bom = bom_scala_constraints(args.bom_pom.resolve())
        if bom != staged:
            raise ValueError("Published BOM Scala constraints differ from staged POMs: " +
                             repr(sorted(bom ^ staged)))
    except (OSError, ET.ParseError, ValueError) as error:
        raise SystemExit(str(error))
    rendered = json.dumps({"scala_poms": sorted(staged), "bom_constraints": sorted(bom)}, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered)
    else:
        sys.stdout.write(rendered)


if __name__ == "__main__":
    main()
