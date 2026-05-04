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

## Configuration

The app reads the Azure OpenAI API key from (in order):

1. `AZURE_OPENAI_API_KEY` env var
2. `apikey.txt` in the working directory (gitignored, never commit)

The endpoint, deployment, and API version can be overridden via
`AZURE_OPENAI_ENDPOINT`, `AZURE_OPENAI_DEPLOYMENT`, `AZURE_OPENAI_API_VERSION`.

