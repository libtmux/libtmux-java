# Agent instructions

Follow the existing project conventions and keep changes narrowly scoped to
what was asked for.

These hold for every change, whatever it touches:

- Make the smallest coherent change that solves the verified problem; keep
  unrelated cleanup out of it.
- Reuse an existing type, helper, or test before adding a new one.
- Keep new API package-private until a caller outside the package needs it.
- Add a file only for a durable boundary — a distinct responsibility,
  independent reuse, or splitting an oversized type — never for a single-use
  helper or a one-line re-export.
- A passing gate is evidence only once it has been shown capable of failing.
  Pair a new test with a deliberate break that proves it bites.
- Every tmux server this project starts belongs under `/tmp/libtmux-java-test/`
  or `/tmp/libtmux-java-dev/` — never a bare `/tmp/libtmux-…`, which a sibling
  port on this machine is also using. `.github/CONTRIBUTING.md` explains what
  goes wrong when they share.

## Additional guidance

This file routes; it does not restate. Read the policy that governs the change
being made:

- Documentation and user-facing prose — `README.md`, `CHANGELOG.md`, release
  notes, commit messages, CLI and help text, API documentation, and source
  comments: [`.github/WRITING.md`](.github/WRITING.md)
- Contribution workflow, building, testing, and pull requests:
  [`.github/CONTRIBUTING.md`](.github/CONTRIBUTING.md)
- Security-sensitive changes: [`SECURITY.md`](SECURITY.md)
- Cutting a release: [`RELEASING.md`](RELEASING.md)
