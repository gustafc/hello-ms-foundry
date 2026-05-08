# Plan: Command-line config + initial prompt + one-shot mode

Right now every knob is set via env vars (`AZURE_OPENAI_DEPLOYMENT`,
`CHATBOT_TOOL_ROOT`, …) and the bot always drops into the REPL. This plan
adds:

1. A real CLI argument layer so you can override anything per-invocation
   without exporting env vars.
2. Trailing positional args → joined into a single initial user prompt,
   echoed back as if the user had typed it.
3. A flag that makes the bot answer the initial prompt and exit, with the
   echoed prompt suppressed (so stdout is just the answer, scriptable).

Precedence everywhere: **CLI flag > env var > built-in default**.

## 1. Scope

In scope:
- One option per existing config knob; same names/semantics, just exposed
  on the command line as well.
- Picocli for parsing, help, and env-var fallback wiring.
- `--help` / `-h` and `--version` for free.
- `[prompt...]` positional args, joined with single spaces.
- `--batch` flag for one-shot use.

Out of scope (for v1):
- Reading prompts from a file (`-f prompt.txt`) or stdin piping (already
  works fine: pipe a line in, REPL eats it).
- A config file (`~/.chatbotrc`, dotenv). The env-var layer covers that.
- Tab completion, JSON output, multi-deployment routing.
- Bash-completion script generation (picocli supports it; not worth the
  build complexity yet).

## 2. Resolved decisions / open questions

1. **Library: picocli.** ✅ Annotation-driven, single jar (~410 KB), zero
   transitive deps, shades cleanly, generates polished `--help`. The Java
   community default for new CLIs. Alternatives considered: JCommander
   (similar but feels older), Apache Commons CLI (no annotations, verbose
   for this many options), hand-rolled (the user explicitly said to add
   a lib).
2. **Precedence wiring.** ✅ Use picocli's `defaultValue` interpolation
   (`"${env:AZURE_OPENAI_DEPLOYMENT:-gpt-5.2-chat}"`) for the simple
   string options. The API key keeps its custom resolver
   (CLI → env → `apikey.txt` → fail) because the file fallback doesn't
   fit the interpolation form.
3. **`CHATBOT_TOOLS=off` semantics.** ✅ Replace the loose
   `off/false/0/no` parsing with picocli's `negatable = true`, exposing
   `--tools` and `--no-tools`. Env var becomes a strict
   `true`/`false`/`on`/`off` parser (still permissive enough). Keeps
   the user-visible behaviour while shrinking the code.
4. **Flag name for one-shot.** ✅ `--batch`. No short alias — `-b` is
   free but unnecessary; the flag will mostly appear in scripts where a
   couple of extra characters cost nothing.
5. **Initial-prompt echo format.** ✅ Print `> <prompt>` followed by a
   newline, then the streamed reply, then a blank line — exactly the
   pattern the REPL produces when you type live, so a recorded session
   and a `bin/java -jar … "what is mejsla?"` look identical.
6. **Banner + tool-trace stream.** ✅ Send the startup banner and tool
   traces to **stderr** unconditionally; reserve stdout for the
   conversation. The trace already goes to stderr today, so this is a
   one-line change to the banner. Interactive users see no visible
   difference; `--batch` consumers get a clean stdout for capture.

## 3. Tech choices

- **picocli 4.7.6** added to `pom.xml`. No annotation processor — the
  reflection-based path is fine for a CLI of this size and avoids extra
  build wiring.
- No other dependency changes. Shade plugin already strips
  `META-INF/*.SF|*.DSA|*.RSA`; picocli is unsigned, but the existing
  filter is harmless on it.

## 4. Project layout (additions)

```
src/main/java/se/mejsla/chatbot/
├── Main.java          # thin: parses args via picocli, hands off
└── ChatbotCommand.java  # @Command class, holds option fields + run logic
```

`Main.main(args)` shrinks to:

```java
System.exit(new CommandLine(new ChatbotCommand()).execute(args));
```

`ChatbotCommand implements Callable<Integer>` owns what is currently in
`Main.main` — banner, registry/dispatcher wiring, REPL dispatch. The
existing `Main.Config` record either stays (built from the command
fields) or is folded into `ChatbotCommand` directly. Folding it in is
fine — it was always just a value bag.

## 5. CLI surface

```
hello-ms-foundry [OPTIONS] [PROMPT...]

Options:
  -d, --deployment=<name>       Azure OpenAI deployment name.
                                Env: AZURE_OPENAI_DEPLOYMENT
                                Default: gpt-5.2-chat
      --endpoint=<url>          Azure OpenAI endpoint.
                                Env: AZURE_OPENAI_ENDPOINT
                                Default: https://bybrick-tech-openai-play.openai.azure.com/
      --api-version=<ver>       API version.
                                Env: AZURE_OPENAI_API_VERSION
                                Default: 2025-01-01-preview
      --api-key=<key>           Azure OpenAI API key (plain).
                                Env: AZURE_OPENAI_API_KEY
                                Fallback: apikey.txt in cwd
      --api-key-file=<path>     Read API key from this file instead.
      --tool-root=<dir>         Sandbox root for read_file.
                                Env: CHATBOT_TOOL_ROOT
                                Default: current working directory
      --tools / --no-tools      Enable/disable tool calling.
                                Env: CHATBOT_TOOLS (true/false)
                                Default: enabled
      --batch                   Answer the initial prompt and exit.
                                Requires a non-empty PROMPT. The echoed
                                "> <prompt>" line is suppressed so stdout
                                is just the model's reply.
  -h, --help                    Show this help and exit.
  -V, --version                 Show version and exit.

Positional:
  [PROMPT...]                   Optional initial prompt. All trailing args
                                are joined with single spaces. Useful with
                                or without --batch.
```

`-h` / `--help` should also print the precedence rule (`CLI > env >
default`) in the footer so users don't have to infer it.

## 6. Config precedence

Picocli wires this declaratively for the simple cases:

```java
@Option(names = {"-d", "--deployment"},
        defaultValue = "${env:AZURE_OPENAI_DEPLOYMENT:-gpt-5.2-chat}")
String deployment;
```

…which means: if the user passed `-d foo`, `deployment` is `"foo"`; else
if `AZURE_OPENAI_DEPLOYMENT` is set, it wins; else `"gpt-5.2-chat"`.
Same pattern for `--endpoint`, `--api-version`, `--tool-root`.

Two custom cases that don't fit interpolation:

- **API key.** Resolution stays imperative:
  1. `--api-key` value, if non-blank.
  2. `--api-key-file` contents, if set.
  3. `AZURE_OPENAI_API_KEY` env, if set.
  4. `apikey.txt` next to cwd, if it exists.
  5. Fail with the existing clear-message exception.
- **`--tools` / `--no-tools`.** Picocli `negatable = true`. If neither
  flag is passed, fall back to `CHATBOT_TOOLS` env (parsed strictly,
  `true|false|on|off|1|0|yes|no`); else default to enabled.

## 7. Initial-prompt + one-shot behaviour

```java
List<String> initial = positional == null ? List.of() : positional;
String initialPrompt = String.join(" ", initial).trim();

if (cmd.batch && initialPrompt.isEmpty()) {
    System.err.println("--batch requires a non-empty PROMPT");
    return 2;  // picocli convention: usage error
}

if (!initialPrompt.isEmpty() && !cmd.batch) {
    System.out.println("> " + initialPrompt);
}

if (!initialPrompt.isEmpty()) {
    streamOneTurn(session, initialPrompt);
    if (cmd.batch) {
        return 0;
    }
}

runRepl(session);  // existing loop
return 0;
```

Where `streamOneTurn` is the body of one iteration of the REPL loop,
extracted so both call sites use it identically (same error handling,
same trailing blank lines). No behavioural divergence between the
"initial-prompt then REPL" path and "type the prompt yourself".

Banner/tool-trace placement:

- Today the banner goes to **stdout** (`System.out.println(...)` in
  `Main.main`).
- Move it to **stderr** so `--batch` produces a stdout that contains
  *only* the model's reply. Tool traces already go to stderr; this
  aligns the rest. Interactive users see no visible difference
  (terminals interleave the streams).

## 8. Build & run

`pom.xml` gets one new dependency:

```xml
<dependency>
    <groupId>info.picocli</groupId>
    <artifactId>picocli</artifactId>
    <version>4.7.6</version>
</dependency>
```

Examples:

```sh
./bin/java -jar target/hello-ms-foundry.jar
# → REPL, defaults

./bin/java -jar target/hello-ms-foundry.jar "What is Mejsla?"
# → prints '> What is Mejsla?' then streams reply, then drops to REPL

./bin/java -jar target/hello-ms-foundry.jar --batch \
    "Summarise pom.xml in one sentence."
# → streams reply, exits 0. Stdout is just the reply.

./bin/java -jar target/hello-ms-foundry.jar -d gpt-4.1-nano --no-tools
# → REPL using a different deployment, tool calling off
```

## 9. Implementation order

1. **Add the dep, scaffold `ChatbotCommand`.** Empty options, just
   `--help` / `--version` working. Confirms shading is unaffected.
2. **Port existing config to picocli.** Move every default into
   `${env:VAR:-default}` interpolation, drop `Main.Config.load`. Run the
   bot interactively — should be byte-identical to before, just with
   `--help` available.
3. **API key + `--tools/--no-tools`.** The two awkward cases. Smoke-test
   each combination (CLI key, env key, file key; tools on/off via flag
   vs env).
4. **Initial-prompt echo, no exit.** `[PROMPT...]` joined, printed as
   `> …`, run one turn, drop into REPL. Verify history contains both
   the initial user turn and the REPL turns afterwards.
5. **`--batch`.** One-shot exit + suppressed echo + banner-to-stderr.
   Verify with a piped command:
   `bin/java -jar … --batch "say hi" | wc -l` should give a sensible
   count of just the reply.
6. **Doc sweep.** Update [`README.md`](../README.md), the env-var section
   of [`AGENTS.md`](../AGENTS.md), and the **Configuration** table in
   [`specs/plan.md`](plan.md) to mention the CLI flags.

## 10. Risks / things that will probably go wrong

- **Picocli env-var interpolation syntax.** It's `${env:NAME:-default}`,
  *not* `${env:NAME:default}` or shell-style `${NAME:=default}`. Easy to
  get wrong; verify each option resolves correctly with `--help`
  (defaults are rendered there) before declaring done.
- **`negatable = true` on `--tools`.** Picocli generates a synthetic
  `--no-tools`; that name must not collide with any other option
  (`--no-tool-root` etc — none exist, but worth a grep before adding
  more flags later).
- **Banner-to-stderr regression.** Anyone scripting against the current
  stdout (none that we know of, but possible) will see a behaviour
  change. Worth a one-line mention in the README so it isn't silent.
- **Stdin already at EOF.** When a script does
  `bot --batch "hi" < /dev/null`, the REPL would normally hit EOF and
  quit cleanly — but with the one-shot path we never enter the REPL,
  so this isn't a concern. Worth verifying anyway.
- **Empty-prompt edge.** `bot --batch ""` (single empty positional)
  trims to empty — return the usage error, don't silently no-op.
- **Trailing-arg quoting.** `bot why is the sky blue?` shell-splits into
  five args; we join with single spaces, dropping the user's original
  spacing. This is fine in practice (users type one spaceful phrase
  inside quotes when it matters). Document the join behaviour in
  `--help`.
