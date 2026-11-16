# release-checks

The decisions `ci.yml` and `release.yml` make around a release, as commands a
workflow calls one at a time:

- `release-version` takes the version a tag or a manual run names and refuses a
  snapshot or any spelling this project does not publish. It writes `version=` to
  `$GITHUB_OUTPUT`.
- `staged-version` reads `libtmuxVersion` from `gradle.properties`, drops
  `-SNAPSHOT`, and exports it as `VERSION`, so the staged-release rehearsal builds
  the version a release would publish.
- `ci-passed` requires a successful `ci.yml` run for the commit. CI is not re-run
  for a release; a run still in progress, or never made, refuses it.
- `carry-passphrase` passes the signing key's passphrase on to Gradle only when
  there is one, because the publisher does not treat a blank value as absent.

```console
$ uv run scripts/release-checks/release_checks.py release-version --ref-name v0.1.2
```

`gh run list` stops after 60 s, which is one API request, and a failure to list
the runs refuses the release with a `::error::` annotation.

A refused check prints a `::error::` annotation and exits 1. The tests call each
decision, and `ci-passed` against a stand-in `gh`.

Run the tests with the [script runner](../README.md#running-the-checks).
