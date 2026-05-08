# Plan: Add a `read_file` tool to the Mejsla chatbot

Give the model the ability to read files from the user's machine via Azure
OpenAI tool calling. The persona stays the same; the bot just grows one new
capability — when a question requires looking at a file, it asks for it.

## 1. Scope

In scope:
- A single tool, `read_file`, that takes a path and returns the file's
  contents as text.
- Multi-turn tool-call loop inside one user turn: model emits `tool_calls` →
  we execute → we feed the result back → model produces the final answer (or
  another tool call).
- Sensible guardrails: path confined to a configured root, size cap, text
  decoding only (UTF-8), clear errors surfaced back to the model.
- Works with the existing streaming REPL.

Out of scope (for v1):
- Writing, listing, globbing, or deleting files.
- Binary-file handling, partial reads / line ranges, encoding detection.
- Parallel tool calls, tool-call cancellation, user-prompted approval flow.
- A second tool — once `read_file` works, more can be slotted into the same
  registry without re-architecting.

## 2. Resolved decisions / open questions

1. **Streaming + tool calls.** ✅ Keep streaming for the *final* assistant
   message, but the tool-call planning step runs blocking. The Azure SDK
   exposes tool-call deltas in streamed responses, but assembling them across
   chunks is fiddly; doing the planner call non-streaming is much simpler and
   the latency hit is small (it's a short response). Once a tool turn is done
   and we're confident the next response is plain text, switch back to
   `streamChat`.
2. **Sandbox root.** ✅ Default to the process's current working directory.
   Overridable via `CHATBOT_TOOL_ROOT`. Reject any path that, after
   canonicalisation, escapes the root. No symlink-following past the root.
3. **Size cap.** ✅ 256 KiB. Big enough for typical source files, small
   enough that one tool call can't blow up the context window or the
   terminal. Larger reads return an error the model can relay.
4. **Path semantics.** ✅ Relative paths resolved against the root; absolute
   paths allowed only if they fall *inside* the root after canonicalisation.
5. **User visibility.** ✅ Print a one-line trace per tool call to stderr
   (`[tool] read_file path=… bytes=…`) so the user can see what's happening
   without polluting the chat stream on stdout.
6. **Open: approval prompts.** Skipped for v1 (sandbox + size cap are
   considered sufficient). If we later want a `--ask` mode, hook it in
   `ToolDispatcher` before invoking the tool.

## 3. Tech choices

- **SDK surface:** stay on `com.azure:azure-ai-openai` 1.0.0-beta.16. It
  already supports tools via `ChatCompletionsFunctionToolDefinition` /
  `ChatRequestToolMessage` / `ChatResponseMessage.getToolCalls()`. No new
  dependency needed.
- **JSON for tool args:** the SDK exposes function arguments as a JSON
  string. We need to parse `{"path": "..."}`. Pull in
  `com.fasterxml.jackson.core:jackson-databind` (already transitive via the
  Azure SDK — verify with `mvn dependency:tree` and use the resolved version
  rather than re-declaring it if possible). Keep schemas hand-written as JSON
  strings; no schema-generation library.
- **Path handling:** `java.nio.file.Path` + `toRealPath(NOFOLLOW_LINKS)` for
  canonicalisation. No third-party path libs.

## 4. Project layout (additions)

```
src/main/java/se/mejsla/chatbot/
├── tools/
│   ├── Tool.java             # interface: name, schema, execute(JsonNode) -> String
│   ├── ToolRegistry.java     # name -> Tool; builds the SDK tool definitions
│   ├── ToolDispatcher.java   # parses tool_calls, runs them, returns ChatRequestToolMessage list
│   └── ReadFileTool.java     # the actual implementation
└── ChatSession.java          # extended with the tool-call loop
```

`AzureOpenAIClient` grows one method that takes the tool list and returns the
*raw* `ChatResponseMessage` (so `ChatSession` can see tool-call metadata),
plus keeps `streamChat` for the final pass.

## 5. Tool contract

JSON schema sent to the model:

```json
{
  "name": "read_file",
  "description": "Read a UTF-8 text file from the user's machine. Returns the file contents.",
  "parameters": {
    "type": "object",
    "properties": {
      "path": {
        "type": "string",
        "description": "Path to the file. Relative paths resolve from the chatbot's working directory. The file must live inside the chatbot's sandbox root."
      }
    },
    "required": ["path"],
    "additionalProperties": false
  }
}
```

Execution result format (returned as the `tool` message content):

- **Success:** the raw file contents, prefixed with a small header so the
  model knows where it came from and how big it is, e.g.
  `--- read_file path=src/main/java/.../Main.java bytes=4231 ---\n<contents>`.
- **Failure:** a one-line `error: <reason>` string. The model is good at
  recovering from these (asking for a different path, giving up gracefully).
  Reasons we surface: `not found`, `outside sandbox`, `too large (limit
  262144 bytes, got N)`, `not a regular file`, `not valid UTF-8`,
  `unreadable: <io message>`.

We never throw out of the tool — every failure becomes a structured string
result. Throwing would abort the turn and confuse the model.

## 6. Conversation flow with tools

`ChatSession.streamReply` becomes:

1. Append the user message to history.
2. **Plan loop** (bounded — say 4 iterations max to avoid runaway tool
   chains):
   - Call `client.chatWithTools(history, tools)` (blocking).
   - If the response has no `tool_calls`, treat it as the final assistant
     message: append to history, emit its content to the chunk sink, break.
     (Optionally: re-issue the same prompt with `streamChat` so the user
     gets streamed output. Cheaper alternative: just print the blocking
     response in one go — fine for v1.)
   - If the response has `tool_calls`: append the assistant message *with*
     its tool-call metadata to history (the SDK requires this for the next
     turn to validate), execute each call via `ToolDispatcher`, append one
     `ChatRequestToolMessage` per call, loop.
3. If the iteration cap is hit, append a synthetic assistant message saying
   "stopped after N tool calls" and return that to the user.

Stream the final assistant turn if cheap; otherwise print it whole. Do not
stream while tool calls are in flight — tool-call deltas in streamed
responses are awkward to reassemble in this SDK.

## 7. Sandboxing details (the part most likely to bite)

- Resolve the root once at startup: `Path root = Paths.get(env or
  "").toAbsolutePath().normalize()`.
- For each call:
  1. `Path requested = root.resolve(arg).normalize()` — relative paths land
     under root; absolute paths bypass the resolve but `normalize` collapses
     `..`.
  2. `Path real = requested.toRealPath()` — resolves symlinks, fails fast on
     missing files.
  3. Reject if `!real.startsWith(root.toRealPath())`.
  4. Reject if `!Files.isRegularFile(real)`.
  5. Check `Files.size(real) <= MAX_BYTES` *before* reading.
  6. `Files.readString(real, UTF_8)` — throws `MalformedInputException` for
     non-UTF-8 files; catch and report.
- Don't log the file contents on stderr, only the path and size.

## 8. Configuration (additions)

| Setting              | Source                          | Default                         |
|----------------------|---------------------------------|---------------------------------|
| Tool sandbox root    | env `CHATBOT_TOOL_ROOT`         | process working directory       |
| Tools enabled        | env `CHATBOT_TOOLS` (`on`/`off`)| `on`                            |
| Max tool iterations  | constant in code                | 4                               |
| Max file size        | constant in code                | 262144 (256 KiB)                |

`CHATBOT_TOOLS=off` is the escape hatch for "give me the old behaviour" if
tool calling causes trouble — `ChatSession` falls back to plain `streamChat`.

## 9. System-prompt tweak

Add a short paragraph to `system-prompt.md` telling the bot the tool exists,
when to use it ("when the user references a file, or when reading code would
let you give a more accurate answer"), and when *not* to ("don't speculate
about files the user hasn't mentioned; ask first if unsure"). Keep the
Mejsla persona untouched.

## 10. Build & run

No new build steps. `bin/mvn -q package` continues to produce the runnable
shaded jar. Verify with `bin/mvn dependency:tree` that `jackson-databind` is
already on the classpath via the Azure SDK; if not, add it explicitly to
`pom.xml`. (Per repo convention, always use `bin/mvn` and `bin/java`, not
the bare commands.)

## 11. Implementation order

1. **Tool plumbing without a real tool.** Add `Tool`, `ToolRegistry`,
   `ToolDispatcher`, register a no-op `echo` tool, wire the loop into
   `ChatSession`. Verify that asking the bot to "echo hello" round-trips a
   tool call. This isolates the SDK glue from the file logic.
2. **`ReadFileTool` happy path.** Path validation, size check, UTF-8 read,
   header-prefixed output. Manual test: ask the bot to summarise
   `pom.xml`.
3. **Error paths.** Force each failure mode (missing file, escape attempt
   with `..`, oversized file via `dd`, binary file). Confirm the model
   handles each gracefully.
4. **Iteration cap & visibility.** Tool-call trace on stderr, cap on
   iterations, manual test with a deliberately recursive prompt.
5. **System-prompt update + README note.** One-paragraph addition to each.

## 12. Risks / things that will probably go wrong

- **Streaming + tool calls.** The SDK's streamed tool-call deltas are split
  across chunks; reassembling them is the kind of code that looks right and
  silently drops the last token. We sidestep this by going blocking during
  tool turns. If we later want streamed tool calls, write tests first.
- **Path traversal.** `Path.resolve` + `normalize` alone is *not* enough —
  symlinks can still escape. `toRealPath()` plus a `startsWith` check on
  the canonical root is the only reliable combination. Add at least one
  test that creates a symlink pointing outside the root and asserts it's
  rejected.
- **Context-window blowup.** A 256 KiB file is ~64k tokens — already enough
  to cost real money on every subsequent turn. Consider trimming history
  after a tool turn (drop old `tool` messages once we have the final
  assistant reply) if costs become an issue.
- **Model invents paths.** Especially early in a conversation, the model may
  guess a plausible-sounding path. The `not found` error is fine — it backs
  off and asks. Watch for it during step 3 testing.
- **Jackson version drift.** If the Azure SDK upgrades and changes its
  Jackson transitive, our hand-rolled parsing breaks. Pin Jackson explicitly
  in `pom.xml` if this becomes flaky.
