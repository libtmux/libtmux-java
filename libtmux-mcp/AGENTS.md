# MCP surface boundary

Follow the repository-level [`AGENTS.md`](../AGENTS.md) and its writing and
contribution guides.

- Treat MCP as a curated, semantic, detached-safe surface. Library parity does
  not imply MCP parity.
- Exclude modal human-client interfaces when a capture or noninteractive
  equivalent exists. Copy mode, clock mode, choose-tree, prompts, menus,
  popups, and mouse gestures are exclusion signals.
- Read text through bounded capture, history, snapshot, search, and
  `capture_since` operations. Report an active mode or read its screen instead
  of entering or cancelling it.
- Avoid operations that require paired cleanup, have unclear ownership, or
  depend on key tables, a mouse, a clipboard, or UI timing.
- Retain typed core library APIs even when MCP omits their commands.
- Assign every public tool to exactly one ADR toolset. The authoritative
  catalog drives runtime registration, documentation, and tests.
