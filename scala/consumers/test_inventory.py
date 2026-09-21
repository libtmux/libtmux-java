"""Critical negative controls for installed-consumer evidence."""

import importlib.util
import hashlib
from pathlib import Path
from types import SimpleNamespace
import sys
import tempfile
import unittest
from zipfile import ZipFile


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/verify-consumers.py"
sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("consumers", SCRIPT)
consumers = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(consumers)


class RuntimeInventoryTest(unittest.TestCase):
    def test_execution_uses_the_reported_facade_not_a_test_eviction(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            java, facade, wrong = (root / name for name in ("java.jar", "facade.jar", "wrong.jar"))
            java.write_bytes(b"java")
            facade.write_bytes(b"facade")
            wrong.write_bytes(b"wrong")
            runtime = [{"coordinate": "io.github.libtmux:" + module + ":test",
                        "sha256": consumers.digest(path)}
                       for module, path in (("libtmux", java), ("libtmux-scala_3", facade))]
            report = root / "test.tsv"
            java_row = f"io.github.libtmux\tlibtmux\ttest\t{java}\n"
            report.write_text(java_row + f"io.github.libtmux\tlibtmux-scala_3\ttest\t{facade}\n")
            consumers.verify_test_runtime(report, runtime)
            for version, path in (("other", facade), ("test", wrong)):
                report.write_text(java_row + f"io.github.libtmux\tlibtmux-scala_3\t{version}\t{path}\n")
                with self.assertRaisesRegex(ValueError, "runtime dependency under test"):
                    consumers.verify_test_runtime(report, runtime)

    def test_new_repository_build_fence_cannot_hide_from_old_exports(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            exports = root / "target/exports"
            (exports / "scala-doc-build").mkdir(parents=True)
            lines, inventory = [], []
            code = 'val version = "test"\n'
            for name in ("install-staged-core", "install-staged-cats", "install-direct-java"):
                opening = len(lines) + 2
                lines.extend(["<!-- snippet: scala-build: " + name + " -->", "```sbt", code.strip(), "```", ""])
                inventory.append(f"README.md\t{opening}\t{name}\tbuild\t" + hashlib.sha256(code.encode()).hexdigest())
                (exports / "scala-doc-build" / (name + ".sbt")).write_text(code)
            (root / "README.md").write_text("\n".join(lines))
            (exports / "scala-docs-inventory.tsv").write_text("\n".join(inventory) + "\n")
            original = consumers.ROOT
            consumers.ROOT = root
            try:
                self.assertEqual(len(consumers.exported_settings(exports, "test")), 3)
                (root / "other").mkdir()
                (root / "other/README.md").write_text("<!-- snippet: scala-build: future-install -->\n```sbt\nval next = 1\n```\n")
                with self.assertRaisesRegex(ValueError, "inventory omits/adds"):
                    consumers.exported_settings(exports, "test")
            finally:
                consumers.ROOT = original

    def test_archives_reject_wrong_bytecode_sources_and_source_links(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binary, sources, docs = (root / name for name in ("binary.jar", "sources.jar", "docs.jar"))
            raw = b"object Example\n"
            source = "example/Example.scala"
            inventory = source + "\t" + hashlib.sha256(raw).hexdigest() + "\n"

            def write(major=65, embedded=raw, anchor="L1"):
                with ZipFile(binary, "w") as jar:
                    jar.writestr("Example.class", b"\xca\xfe\xba\xbe\x00\x00" + major.to_bytes(2, "big"))
                with ZipFile(sources, "w") as jar:
                    jar.writestr(source, raw)
                with ZipFile(docs, "w") as jar:
                    jar.writestr("index.html", '<a href="_sources/' + source + '.html#' + anchor + '">Source</a>')
                    jar.writestr("_sources/sources.tsv", inventory)
                    jar.writestr("_sources/" + source, embedded)
                    jar.writestr("_sources/" + source + ".html", '<span id="L1">object Example</span>')

            write()
            self.assertEqual(consumers.verify_archives(binary, sources, docs)["source_links"], 1)
            for change, diagnostic in (({"major": 66}, "JDK 21"),
                                       ({"embedded": b"wrong"}, "raw source differs"),
                                       ({"anchor": "L2"}, "Broken Scaladoc")):
                with self.subTest(diagnostic=diagnostic):
                    write(**change)
                    with self.assertRaisesRegex(ValueError, diagnostic):
                        consumers.verify_archives(binary, sources, docs)

    def test_runtime_inventory_rejects_false_consumer_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            args = SimpleNamespace(scala_stage=root / "scala", java_stage=root / "java",
                                   version="test", java_version="java-test", scala_version="3.9.0")
            rows = []
            for module, stage, version in (
                ("libtmux", args.java_stage, args.java_version),
                ("libtmux-scala_3", args.scala_stage, args.version),
            ):
                path = consumers.artifact(stage, module, version)
                path.parent.mkdir(parents=True)
                path.write_bytes(module.encode())
                rows.append(f"io.github.libtmux\t{module}\t{version}\t{path}")
            library = root / "scala-library.jar"
            library.write_bytes(b"standard library")
            rows.append(f"org.scala-lang\tscala-library\t2.13.18\t{library}")
            report = root / "runtime.tsv"
            report.write_text("\n".join(rows) + "\n")
            self.assertEqual(len(consumers.runtime_inventory(report, "core", "3", args)), 3)
            report.write_text("\n".join(rows).replace("2.13.18", "3.9.0") + "\n")
            self.assertEqual(len(consumers.runtime_inventory(report, "core", "3", args)), 3)
            for extra, diagnostic in (
                (f"org.typelevel\tcats-core_3\t2.0\t{library}", "Optional runtime"),
                (f"org.typelevel\tcats-core_2.13\t2.0\t{library}", "Mixed Scala"),
                (f"org.scala-lang\tscala-library\t2.12.0\t{library}", "Wrong Scala"),
            ):
                with self.subTest(diagnostic=diagnostic):
                    report.write_text("\n".join(rows + [extra]) + "\n")
                    with self.assertRaisesRegex(ValueError, diagnostic):
                        consumers.runtime_inventory(report, "core", "3", args)
            substituted = root / "substituted.jar"
            substituted.write_bytes(b"different library")
            rows[1] = f"io.github.libtmux\tlibtmux-scala_3\ttest\t{substituted}"
            report.write_text("\n".join(rows) + "\n")
            with self.assertRaisesRegex(ValueError, "differs from stage"):
                consumers.runtime_inventory(report, "core", "3", args)


if __name__ == "__main__":
    unittest.main()
