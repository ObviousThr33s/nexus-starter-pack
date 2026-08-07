# nexus — a starter pack for local agents that do not invent

Two programs and a field journal, sharing one law: **never invent data.**

A local model with no tools will answer anything you ask it, fluently, forever.
Point the same model at a device it can actually read, refuse every claim whose
evidence does not appear in a tool result, and the invention stops. Not because
the model got smarter — it is the same weights — but because it was given eyes
and a fact-checker. That is the whole thesis, and `journal/` is the run that
demonstrates it.

Everything here runs offline against hardware you own. No account, no key, no
telemetry, no vendor.

## What is in the pack

| | |
|---|---|
| `sage-scala/` | SAGE — a windowed agent that reads a modem's own pages and refuses to record anything it did not read. Scala 3, no dependencies beyond the standard library. |
| `git-tools/` | A full-screen browser for commit history across every clone on a machine. Written twice — stdlib Python and a Rust rewrite — sharing the reasoning in `ALGORITHMS.md`. |
| `journal/` | The grounding experiment, four runs, with what each one proved. |
| `.githooks/` | The house rule. See below. |

The two programs never talk to each other. They are here together because they
were built to the same standard, and that standard is the transferable part.

## The law

> **Never invent data.**

Stated in `git-tools/ALGORITHMS.md` §7, enforced in code in
`sage-scala/src/Learnings.scala`. It shows up as a refusal in both:

- git-tools' first version scraped a profile page and, on failure, returned a
  hardcoded list of repository names as though they were real — *the worst
  possible outcome, because it is indistinguishable from success.* Now every
  source raises an error with a cause fit to show a person, and the UI renders
  it rather than papering over it.
- SAGE will not save a learning whose evidence string does not appear in what
  the tools actually returned in that conversation. The model argues. The
  ledger does not care.

A digest that is merely *usually* right is worse than no digest, because you
stop checking. Both programs are built so that the failure mode is a visible
refusal instead of a confident wrong answer.

## And the lies that remain

Never invent data is a direction, not an achievement. Some falsehood survives in
anything that summarises. The discipline is not reaching zero — it is keeping
the count small, and saying where each one is.

Named, in this pack:

- The commit classifier is a heuristic over English subject lines. It is wrong on
  any subject that does not describe its change, which is why its output is
  always presented as a count of subjects and never as a claim about the code.
- The week digest reports activity and cannot report intent. It will tell you
  that four commits reworked `src/render`. It cannot tell you whether the
  refactor was a good idea.
- Over the GitHub API the digest computes counts only, because file statistics
  cost one request per commit. It says so on screen rather than quietly serving
  a thinner answer as though it were the same answer.
- SAGE's guard checks that a claim's evidence appears in what the tools
  returned. It cannot check that the model read that evidence correctly.

Each of those is a known falsehood held at a size you can see. That is the
achievable standard. The dangerous kind is the one nobody wrote down.

## Getting started

### SAGE

Needs a JDK 24 and [scala-cli](https://scala-cli.virtuslab.org)
(`winget install --id VirtusLab.ScalaCLI`). Once `scala3-compiler` is in the
Coursier cache the build needs no network.

```
cd sage-scala
build.cmd
```

That runs the self-test first and refuses to package if it fails. It writes
`sage.jar` one level up. Then:

```
sage-scala.cmd --self-test          the suite, offline
sage-scala.cmd --doctor             what it can see from here
sage-scala.cmd --firewall http://192.168.0.1    ask the guard about a URL
```

Put your device password in `secret.txt` beside the jar and it prefills the
login. That file is gitignored and must stay that way.

For the model half, run anything that speaks `/v1/chat/completions` on
loopback — Ollama, `llama-server`, LM Studio, Jan. SAGE probes for it. With no
model running the tools still work; you simply drive them yourself.

### git-tools

```
cd git-tools
run.bat                    browse the clones under the current directory
run.bat <github-account>    browse an account  (the only networked mode)
run.bat -h                  usage
```

The Rust build takes the same arguments plus `--check`, which prints everything
it finds without needing a terminal at all. `cargo build --offline` works from
the local registry cache; `tools\vendor.ps1` prepares a machine that has never
had network.

## The guard rails, and why they are shaped that way

**Netguard** (`sage-scala/src/Netguard.scala`) allows loopback, RFC1918,
link-local and CGNAT, and blocks everything else — so an agent pointed at your
LAN cannot be talked into fetching a public URL. It is written against the
evasions rather than the happy path: `::ffff:8.8.8.8`, `2002:0808:0808::1`
(6to4), `64:ff9b::808:808` (NAT64) and `169.254.169.254` (cloud metadata, the
classic SSRF target) are each handled by name, with a test asserting the
verdict. `NetguardSuite.scala` is the readable specification.

**The learnings ledger** (`sage-scala/src/Learnings.scala`) is append-only JSON
on disk, keyed by evidence. A claim arrives with the text it came from; if that
text is not in the transcript of tool returns, the save is rejected and the
model is told why. Set `HELIOS_HOME` to choose where it lives.

**No dependency list.** `sage-scala/project.scala` has none and must never gain
one. The JDK supplies HTTP with TLS, a cookie jar, regex, and a GUI. It does not
supply a JSON parser, so there is one in `Json.scala` — about 200 lines, versus
a Maven artifact and the promise that comes with it.

## The house rule

`.githooks/commit-msg` requires every commit title to be **exactly 29
characters**. The origin is that `len("Add the radio line, and the..") == 29`.

It is arbitrary and that is the point: an arbitrary, cheap, mechanically-checked
constraint on every message forces a real edit of every message. Titles stop
being `wip` and `fix stuff`. Merge, revert, fixup, squash and amend subjects are
exempt because they are machine-generated.

To adopt it:

```
git config core.hooksPath .githooks
```

Not for everyone. Delete the directory if you disagree — nothing else depends on
it.

## What this is not

Not a framework. There is no plugin system, no agent abstraction to subclass,
nothing to register. Two programs that work, with their reasoning written down,
are a better start than a framework that assumes what you are building.

Not a chatbot wrapper. SAGE's value is the refusal, not the conversation.

Not a general modem client. It happens to read a ZyXEL C1000Z because that is
the device that was in the room. `Modem.scala` is one file, and swapping it for
whatever you have is the intended first change you make.

## Licence

MIT. See [LICENSE](LICENSE).

`[2026-08-07]`
