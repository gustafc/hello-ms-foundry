# Plan: Mejsla chatbot CLI on Azure OpenAI (Microsoft AI Foundry)

A small Java command-line chatbot that talks to the Azure OpenAI deployment at
`https://bybrick-tech-openai-play.openai.azure.com/` and roleplays a seasoned
Mejsla developer.

## 1. Scope

In scope:
- Single-process CLI app; reads stdin, writes stdout.
- Multi-turn conversation, history kept in memory for the lifetime of the process.
- System prompt that anchors the bot as a senior Java dev working at Mejsla.
- Local-only run; no packaging for distribution beyond a runnable jar.

Out of scope (for v1):
- Persisting chat history between runs.
- Tool/function calling, RAG, or web search.
- Multiple concurrent sessions, auth beyond the static API key.
- A GUI or web frontend.

## 2. Resolved decisions / open questions

1. **Deployment name.** ✅ Defaulting to `gpt-5.2-chat` (backing model
   `gpt-5.2-chat`, 250k TPM). Newest, explicitly chat-tuned. Overridable via
   `AZURE_OPENAI_DEPLOYMENT`. Fallback if it misbehaves: `gpt-4.1-nano`
   (clean name, well-supported by older API versions, cheap).
2. **API version.** Starting at `2025-04-01-preview` because gpt-5 models
   typically need a newer api-version than the GA `2024-10-21`. If the SDK
   complains we bump.
3. **Streaming vs. blocking.** Still open — ship blocking first (step 3),
   swap to streaming in step 6 if the SDK makes it easy.

## 3. Tech choices

- **Language / runtime:** Java 21 (LTS).
- **Build tool:** Maven. Single `pom.xml`, single module — no need for Gradle's
  flexibility here.
- **Toolchain manager:** [SDKMAN!](https://sdkman.io/) pins Java and Maven
  versions per project. Commit a `.sdkmanrc` at the repo root and run
  `sdk env install` once on a fresh checkout. This keeps the Java/Maven
  versions identical across machines without depending on whatever's on
  `PATH`.
- **Azure OpenAI client:** `com.azure:azure-ai-openai` (official Azure SDK for
  Java). Handles auth, retries, and streaming. Avoids hand-rolling HTTP.
- **JSON / HTTP:** transitive via the Azure SDK; no extra deps.
- **CLI input:** plain `java.util.Scanner` on `System.in`. No need for picocli
  for a single REPL.
- **Logging:** SLF4J simple binding at WARN; keep stdout clean for the chat.

## 4. Project layout

```
hello-ms-foundry/
├── .sdkmanrc                  # pins Java + Maven versions for SDKMAN!
├── apikey.txt                 # already exists; gitignored, never committed
├── pom.xml
├── specs/
│   ├── bootstrap.md
│   └── plan.md                # this file
└── src/
    └── main/
        ├── java/se/mejsla/chatbot/
        │   ├── Main.java          # entry point, argument parsing, run loop
        │   ├── ChatSession.java   # holds history, calls the client
        │   ├── AzureOpenAIClient.java  # thin wrapper around the Azure SDK
        │   └── SystemPrompt.java  # builds the Mejsla persona prompt
        └── resources/
            └── system-prompt.md   # the persona text, loaded at startup
```

Four small classes is enough; resist adding more until something forces it.

## 5. Configuration

Resolve config in this order (first hit wins):

| Setting       | Source                                      | Default |
|---------------|---------------------------------------------|---------|
| API key       | env `AZURE_OPENAI_API_KEY`, else `apikey.txt` next to working dir | required |
| Endpoint      | env `AZURE_OPENAI_ENDPOINT`                 | `https://bybrick-tech-openai-play.openai.azure.com/` |
| Deployment    | env `AZURE_OPENAI_DEPLOYMENT`               | TBD (see open questions) |
| API version   | env `AZURE_OPENAI_API_VERSION`              | pinned constant in code |

Trim whitespace/newlines when reading `apikey.txt`. Fail fast with a clear
message if the key is missing.

## 6. The Mejsla persona (system prompt)

Stored as `src/main/resources/system-prompt.md` so it can be edited without a
recompile-rebuild cycle being a hassle. Drafted from the content on
mejsla.se/om and mejsla.se/jobb. Key traits to bake in:

- **Role:** seasoned developer; deep Java expertise, also fluent in adjacent
  languages and platforms (Scala, Kotlin, JVM tooling, build/CI, cloud).
- **Employer:** Mejsla — a small Swedish software consulting firm, acquired by
  byBrick in 2025 but keeping its identity. Java is the shared technical
  foundation across consultants.
- **Voice:** *"driven and unpretentious"*; values craftsmanship (the name
  *mejsla* means "to chisel"); rejects bureaucracy; enjoys knowledge-sharing.
  Practical, direct, not preachy.
- **Stance on craft:** software development is *"much more than programming"* —
  understand the team, the process, and pick the right tools, not just the
  shiny ones.
- **Language:** answers in the language the user writes in (Swedish or
  English); doesn't lecture about Mejsla unless asked, but mentions it
  naturally when relevant.

## 7. Conversation flow

1. Startup: load config, read system prompt, print a one-line greeting (`>`-
   style prompt indicator).
2. Loop:
   - Read a line from stdin. Blank line = ignore. `:exit` / `:quit` / Ctrl-D =
     stop. `:reset` = drop history, keep system prompt.
   - Append the user turn to history.
   - Call the Azure OpenAI chat completions endpoint with the full message
     list.
   - Stream (or print) the assistant turn, append it to history.
3. Shutdown: print a goodbye line and exit 0. Any unhandled exception → print
   message and exit 1.

History is a simple `List<ChatMessage>` in `ChatSession`. No truncation logic
in v1; if a long session ever bumps the context window, address it then.

## 8. Build & run

- `mvn -q package` produces a runnable jar via `maven-shade-plugin` (or just
  use `mvn -q exec:java` during development).
- `java -jar target/hello-ms-foundry.jar` to launch.
- Document both in a short `README.md` once the app actually runs.

## 9. Implementation order

1. ✅ **Skeleton:** `pom.xml`, `Main.java` printing "hello"; `mvn package` +
   `java -jar` confirmed end to end.
2. ✅ **Config loader:** API key from `apikey.txt` / env; masked-key startup
   line confirms wiring.
3. ✅ **One-shot call:** Azure OpenAI replied via `gpt-5.2-chat` /
   `2025-01-01-preview`. Required a shade-plugin signed-jar filter.
4. ✅ **REPL:** read loop + history; `:exit` / `:quit` / Ctrl-D / `:reset`.
5. ✅ **System prompt:** loaded from `src/main/resources/system-prompt.md`,
   prepended to history; persists across `:reset`.
6. ✅ **Streaming:** `OpenAIClient.getChatCompletionsStream` → token chunks
   printed live.
7. ✅ **Polish:** SLF4J at WARN (clean output), graceful Ctrl-D, masked
   API key in startup banner, per-turn error catch so a transient API
   failure doesn't kill the REPL.

## 10. Risks / things that will probably go wrong

- **Wrong deployment name** → 404 from Azure with an opaque message. Mitigate
  by listing deployments via `az cognitiveservices account deployment list`
  or the Azure portal first.
- **API version mismatch** → fields silently missing. Pin a recent stable
  version and don't drift.
- **`apikey.txt` accidentally committed.** Add a `.gitignore` the moment the
  repo is `git init`-ed; the file already exists in the working tree.
- **Encoding on Windows consoles** — not a concern for the user's macOS box,
  but the SDK handles UTF-8 cleanly anyway.
