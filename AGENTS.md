# AGENTS.md

Guidance for AI coding agents working in this repository. Humans should read
[README.md](README.md) and [specs/plan.md](specs/plan.md) — they cover the
same ground less tersely.

## What this is

A small Java 21 CLI chatbot that talks to Azure OpenAI (Microsoft AI
Foundry) and roleplays a seasoned Mejsla developer. Single Maven module,
~5 source files. The persona is not in code — edit
[`src/main/resources/system-prompt.md`](src/main/resources/system-prompt.md)
and rebuild to change tone or content.

## Toolchain — always use the wrappers

This project pins Java and Maven via [SDKMAN!](https://sdkman.io/) in
[`.sdkmanrc`](.sdkmanrc). Two wrapper scripts handle activation so you don't
have to:

- [`bin/mvn`](bin/mvn) — sources `sdkman-init.sh`, runs `sdk env`, then
  execs `mvn` with your arguments.
- [`bin/java`](bin/java) — same dance, then execs `java`.

**Always invoke `./bin/mvn` and `./bin/java` instead of bare `mvn` / `java`.**
The Bash tool spawns non-interactive shells, where SDKMAN!'s
`sdkman_auto_env` hook does *not* fire. The system JDK (currently 17) wins,
and Maven blows up with "release version 21 not supported". The wrappers
fix this for any shell — interactive or not, with no banner output to
pollute scripted captures.

If you find yourself prepending `source ~/.sdkman/bin/sdkman-init.sh && sdk env`
to a command, stop and use the wrapper instead.

## Build & run

```sh
./bin/mvn -q package
./bin/java -jar target/hello-ms-foundry.jar
```

`mvn package` produces a shaded uber-jar at
`target/hello-ms-foundry.jar`. The shade plugin filters out signed-jar
`META-INF/*.SF|*.DSA|*.RSA` (a transitive Azure dep is signed and breaks
`java -jar` otherwise — don't remove that filter).

## Configuration

Resolved in [`Main.Config`](src/main/java/se/mejsla/chatbot/Main.java) at
startup; first hit wins:

| Setting     | Source                                   | Default                                                  |
|-------------|------------------------------------------|----------------------------------------------------------|
| API key     | `AZURE_OPENAI_API_KEY` → `apikey.txt`    | required (fail-fast)                                     |
| Endpoint    | `AZURE_OPENAI_ENDPOINT`                  | `https://bybrick-tech-openai-play.openai.azure.com/`     |
| Deployment  | `AZURE_OPENAI_DEPLOYMENT`                | `gpt-5.2-chat`                                           |
| API version | `AZURE_OPENAI_API_VERSION`               | `2025-01-01-preview` (latest the SDK enum supports)      |

`apikey.txt` is gitignored. Don't commit it. Don't echo it to logs — the
masked-key startup line is the only place the key should ever appear.

### API version quirk

`com.azure:azure-ai-openai` (currently `1.0.0-beta.16`) takes service version
as an enum, not a free-form string. If `AZURE_OPENAI_API_VERSION` doesn't
match an `OpenAIServiceVersion` constant, the client logs a warning and
falls back to the latest enum value (`V2025_01_01_PREVIEW`). This is
intentional, not a bug — but means you can't actually use api-versions
newer than the SDK without switching to raw HTTP.

## Project layout

```
hello-ms-foundry/
├── .sdkmanrc                          # pinned Java + Maven for SDKMAN!
├── apikey.txt                         # gitignored, never commit
├── bin/{mvn,java}                     # wrappers — use these
├── pom.xml
├── AGENTS.md                          # this file
├── README.md
├── specs/{bootstrap.md,plan.md}
└── src/main/
    ├── java/se/mejsla/chatbot/
    │   ├── Main.java                  # entry point + Config record + REPL
    │   ├── ChatSession.java           # message history; reset() keeps system prompt
    │   ├── AzureOpenAIClient.java     # SDK wrapper; chat() blocking, streamChat() streaming
    │   └── SystemPrompt.java          # classpath loader
    └── resources/
        ├── system-prompt.md           # the Mejsla persona — edit me, no recompile-of-Java needed
        └── simplelogger.properties    # SLF4J at WARN, silences Netty noise
```

Keep this layout small. The plan explicitly says four classes is enough;
resist adding more until something forces it. `Config` lives nested inside
`Main` for the same reason.

## REPL contract

[`Main.runRepl`](src/main/java/se/mejsla/chatbot/Main.java) reads stdin
line by line:

- `:exit` / `:quit` / Ctrl-D / EOF — leave
- `:reset` — clear history but keep the system prompt
- blank line — ignored
- anything else — sent as a user turn; assistant reply is streamed

Per-turn errors are caught so a transient API failure doesn't kill the
loop.

## Common mistakes to avoid

- Running `mvn` / `java` directly in a Bash tool call. Use `./bin/mvn` /
  `./bin/java`. Always.
- Probing `java -version` / `mvn -version` to figure out what's installed.
  Trust [`.sdkmanrc`](.sdkmanrc).
- Suggesting an alternative version manager (asdf, jenv, brew JDKs).
  SDKMAN! is the chosen toolchain.
- Changing `AZURE_OPENAI_API_VERSION` to something outside the SDK's enum
  and expecting it to take effect. It won't — see "API version quirk".
- Logging the raw API key. Mask it (first 4 + last 4 + length).
- Removing the shade-plugin signed-jar filter. The build will produce a
  jar that fails with `SecurityException: Invalid signature file digest`.

## When in doubt

[`specs/plan.md`](specs/plan.md) records the design decisions and why each
step was structured the way it was. Read it before suggesting refactors.
