# Contributing

## The one rule that is not negotiable

**Never invent data.** A code path that returns plausible output when it should
have reported failure will not be merged, however convenient it is. If a source
cannot answer, raise an error with a cause fit to show a person, and let the
caller render it.

This is the reason the pack exists. Everything else here is a preference.

## Commit titles are exactly 29 characters

```
git config core.hooksPath .githooks
```

`.githooks/commit-msg` rejects any subject line that is not exactly 29
characters. Merge, revert, fixup, squash and amend subjects are exempt. The hook
is POSIX `sh` with builtins only — no `sed`, `head` or `grep` — because the
machine it was written on intermittently cannot fork, and a failed fork would
silently yield an empty subject and pass.

Write the body freely; only the title is measured.

## Before you open a pull request

**SAGE.** `build.cmd` runs the self-test and refuses to package if it fails.
Do not bypass it. If you touch `Netguard.scala`, add the case you are changing
to `NetguardSuite.scala` first — that file is the readable specification of what
an agent on this machine is allowed to reach, and a change there is a change to
the security boundary.

**git-tools.** `cargo test` in `git-history-rs/` covers the parts that can be
wrong quietly: word-boundary classification, Sunday alignment, week-state
selection, and that the Rust sigil still matches the Python one byte for byte.
If you change an algorithm in one language, change it in the other and update
`ALGORITHMS.md` — the two implementations sharing their reasoning is the point
of having two.

Every rendered line must satisfy `visible_len(line) == W` for every width. The
harness asserts it across five widths on every screen. It is the single property
that keeps the frame square.

## Dependencies

`sage-scala/project.scala` has no dependency list and must never gain one. It
targets a machine that may have no internet. If the JDK does not provide it,
write it or do without it — `Json.scala` exists because that trade was already
made once.

`git-history-rs` may take crates, but weigh them against a 3 MB binary that
starts instantly. Model inference happens over plain HTTP to loopback
specifically so that no bindings drag a C++ or CUDA toolchain into the build.

## Documentation

`ALGORITHMS.md` is written as reasoning, not as API notes: the problem, the
constraint that shapes the answer, and the method that follows. Rejected
approaches are recorded with the reason they were rejected, because that is
usually what a reader actually needs. Keep that shape.

## Reporting a security issue

Netguard is a security boundary. If you find a way to make an agent reach a
public address through it, please open an issue with the URL form — the whole
file is written against evasions, and a new one is welcome rather than
embarrassing.
