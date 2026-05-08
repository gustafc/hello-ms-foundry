# hello-ms-foundry

Tiny Java CLI chatbot that talks to the Azure OpenAI deployment at
`https://bybrick-tech-openai-play.openai.azure.com/` and roleplays a seasoned
Mejsla developer. See [specs/plan.md](specs/plan.md) for the design.

## Toolchain

Java and Maven versions are pinned in [`.sdkmanrc`](.sdkmanrc) and managed by
[SDKMAN!](https://sdkman.io/). On a fresh checkout:

```sh
sdk env install
```

This project assumes `sdkman_auto_env=true` in `~/.sdkman/etc/config`, so the
correct JDK/Maven activate automatically when you `cd` into the repo. Without
auto-env you'll need to run `sdk env` in each shell, otherwise Maven will pick
up the system Java.

## Build & run

```sh
mvn -q package
java -jar target/hello-ms-foundry.jar
```

You'll get a REPL. Type messages, get responses streamed back. Commands:

- `:exit` / `:quit` (or Ctrl-D) — leave
- `:reset` — clear conversation history (keeps the system prompt)

The assistant mirrors your language: Swedish in → Swedish out, English in →
English out. The persona is defined in
[`src/main/resources/system-prompt.md`](src/main/resources/system-prompt.md)
and can be edited without recompiling code (only the resource).

### Initial prompt and one-shot mode

Trailing positional args are joined with single spaces and submitted as
the first user turn:

```sh
java -jar target/hello-ms-foundry.jar "What does Mejsla do?"
```

The prompt is echoed (`> <prompt>`), the reply streams, then you drop
into the REPL. Add `--batch` to suppress the echo and exit after the
reply — useful for piping:

```sh
java -jar target/hello-ms-foundry.jar --batch "Summarise pom.xml" > out.txt
```

The startup banner and tool traces go to **stderr**; stdout is reserved
for the conversation. Run `--help` for the full option list.

## Configuration

Every option can be set on the command line, via an environment variable,
or left at its built-in default. Resolution order is **CLI flag > env
var > default** for all of them.

| Setting       | CLI flag                  | Env var                     |
|---------------|---------------------------|-----------------------------|
| API key       | `--api-key`, `--api-key-file` | `AZURE_OPENAI_API_KEY` (then `apikey.txt`) |
| Endpoint      | `--endpoint`              | `AZURE_OPENAI_ENDPOINT`     |
| Deployment    | `-d`, `--deployment`      | `AZURE_OPENAI_DEPLOYMENT`   |
| API version   | `--api-version`           | `AZURE_OPENAI_API_VERSION`  |
| Tool sandbox  | `--tool-root`             | `CHATBOT_TOOL_ROOT`         |
| Tools enabled | `--tools` / `--no-tools`  | `CHATBOT_TOOLS`             |

`apikey.txt` lives in the working directory, is gitignored, and is the
last-resort source for the API key.

## Tools

The bot can read files from your machine via Azure OpenAI tool calling.
A single tool, `read_file`, is enabled by default and confined to a
sandbox root (the working directory unless overridden). Reads are capped
at 256 KiB and must be UTF-8 text. Tool calls are traced to stderr as
`[tool] read_file …` so you can see what's happening.

- `CHATBOT_TOOLS=off` — disable tool calling and revert to plain
  streaming chat.
- `CHATBOT_TOOL_ROOT=/some/path` — override the sandbox root. Anything
  resolving outside the root (after symlink canonicalisation) is rejected.

See [specs/read-file-tool.md](specs/read-file-tool.md) for the design.

