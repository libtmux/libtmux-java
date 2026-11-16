# verify-staged-release

Checks a staged release against what the Central Portal refuses. The release
rehearsal in `.github/workflows/ci.yml` runs it after
`publishAllPublicationsToStagingRepository`, and a maintainer can run it over a
local staging the same way.

```console
$ uv run scripts/verify-staged-release/verify_staged_release.py 0.1.2
```

The second argument is the staging repository, `build/staging-repository` by
default. The script reads the staged BOM, then fails with one `::error::` line
for each of these:

- an artifact the BOM manages that was not staged;
- a missing POM, jar, sources jar or javadoc jar;
- a file without its `.asc`, `.md5` or `.sha1`, a `.sha1` that does not match, or
  a signature `gpg` does not verify, which needs the signing key's public half
  in the keyring;
- a POM without `name`, `description`, `url`, `license`, `developer`, `scm` or
  `connection`, or one that names a snapshot;
- a javadoc jar without `index.html`;
- a jar without `META-INF/libtmux/<module>/CHANGELOG.md`, or whose changelog
  does not open with ``Notable changes to `<module>`.``;
- a sources jar with no `.java`, `.kt`, `.scala` or `.clj` file.

`gpg --verify` stops after 10 s (`bounded.PROBE`); a signature that cannot be
checked counts as not verifying.

The tests stage a small repository signed by a throwaway key and break it one
way at a time. They need `gpg` and skip without it, except when `CI` is set.

```console
$ uv run pytest scripts/verify-staged-release
```
