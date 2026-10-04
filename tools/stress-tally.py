#!/usr/bin/env python3
"""Counts, per test, how often it failed over repeated runs of the suite.

    stress-tally.py add COUNTS.json [ROOT]      fold every JUnit XML report under ROOT into COUNTS.json
    stress-tally.py report COUNTS.json CSV MD   write the CSV and a Markdown table of the failing tests

Run `add` after each repetition, before the reports are deleted for the next one.
"""
import json
import os
import sys
import xml.etree.ElementTree as ET


def reports(root):
    for directory, _, files in os.walk(root):
        if os.sep + "test-results" + os.sep not in directory + os.sep:
            continue
        for name in files:
            if name.startswith("TEST-") and name.endswith(".xml"):
                yield os.path.join(directory, name)


def add(counts_path, root):
    counts = json.load(open(counts_path)) if os.path.exists(counts_path) else {}
    for path in reports(root):
        for case in ET.parse(path).getroot().iter("testcase"):
            key = f"{case.get('classname')}.{case.get('name')}"
            entry = counts.setdefault(key, {"runs": 0, "failures": 0, "skipped": 0})
            if case.find("skipped") is not None:
                entry["skipped"] += 1
                continue
            entry["runs"] += 1
            if case.find("failure") is not None or case.find("error") is not None:
                entry["failures"] += 1
    json.dump(counts, open(counts_path, "w"), indent=1, sort_keys=True)


def report(counts_path, csv_path, md_path):
    counts = json.load(open(counts_path)) if os.path.exists(counts_path) else {}
    with open(csv_path, "w") as csv:
        csv.write("test,runs,failures,skipped\n")
        for key, entry in sorted(counts.items()):
            csv.write(f'"{key}",{entry["runs"]},{entry["failures"]},{entry["skipped"]}\n')
    failing = {k: e for k, e in counts.items() if e["failures"]}
    runs = max((e["runs"] for e in counts.values()), default=0)
    with open(md_path, "w") as md:
        md.write(f"{len(counts)} tests, up to {runs} runs each, {len(failing)} failed at least once.\n\n")
        if failing:
            md.write("| test | failures | runs |\n| --- | ---: | ---: |\n")
            for key, entry in sorted(failing.items(), key=lambda kv: -kv[1]["failures"]):
                md.write(f"| `{key}` | {entry['failures']} | {entry['runs']} |\n")


if __name__ == "__main__":
    command, *rest = sys.argv[1:]
    if command == "add":
        add(rest[0], rest[1] if len(rest) > 1 else ".")
    elif command == "report":
        report(*rest)
    else:
        sys.exit(__doc__)
