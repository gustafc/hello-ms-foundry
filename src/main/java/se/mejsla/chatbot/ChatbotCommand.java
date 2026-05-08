package se.mejsla.chatbot;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import se.mejsla.chatbot.tools.ReadFileTool;
import se.mejsla.chatbot.tools.ToolDispatcher;
import se.mejsla.chatbot.tools.ToolRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "hello-ms-foundry",
        mixinStandardHelpOptions = true,
        version = "hello-ms-foundry 0.1.0",
        description = "Mejsla chatbot CLI on Azure OpenAI.",
        footer = {
                "",
                "Resolution order for every option: command-line flag > environment variable > default."
        })
public final class ChatbotCommand implements Callable<Integer> {

    private static final Path API_KEY_FILE = Path.of("apikey.txt");

    @Option(names = {"-d", "--deployment"}, paramLabel = "<name>",
            description = "Azure OpenAI deployment name. Env: AZURE_OPENAI_DEPLOYMENT. Default: gpt-5.2-chat.",
            defaultValue = "${env:AZURE_OPENAI_DEPLOYMENT:-gpt-5.2-chat}")
    String deployment;

    @Option(names = "--endpoint", paramLabel = "<url>",
            description = "Azure OpenAI endpoint. Env: AZURE_OPENAI_ENDPOINT. Default: https://bybrick-tech-openai-play.openai.azure.com/.",
            defaultValue = "${env:AZURE_OPENAI_ENDPOINT:-https://bybrick-tech-openai-play.openai.azure.com/}")
    String endpoint;

    @Option(names = "--api-version", paramLabel = "<ver>",
            description = "Azure OpenAI API version. Env: AZURE_OPENAI_API_VERSION. Default: 2025-01-01-preview.",
            defaultValue = "${env:AZURE_OPENAI_API_VERSION:-2025-01-01-preview}")
    String apiVersion;

    @Option(names = "--api-key", paramLabel = "<key>",
            description = "Azure OpenAI API key. Env: AZURE_OPENAI_API_KEY. Falls back to apikey.txt.",
            defaultValue = "${env:AZURE_OPENAI_API_KEY}")
    String apiKey;

    @Option(names = "--api-key-file", paramLabel = "<path>",
            description = "Read the API key from this file instead of --api-key / env / apikey.txt.")
    Path apiKeyFile;

    @Option(names = "--tool-root", paramLabel = "<dir>",
            description = "Sandbox root for the read_file tool. Env: CHATBOT_TOOL_ROOT. Default: current working directory.",
            defaultValue = "${env:CHATBOT_TOOL_ROOT:-.}")
    Path toolRoot;

    @Option(names = "--tools", negatable = true,
            description = "Enable (--tools) or disable (--no-tools) tool calling. Env: CHATBOT_TOOLS (true|false|on|off|0|1|yes|no). Default: enabled.")
    Boolean toolsFlag;

    @Option(names = "--batch",
            description = "Answer the initial PROMPT and exit. Requires a non-empty PROMPT. The echoed '> <prompt>' line is suppressed so stdout contains just the model's reply.")
    boolean batch;

    @Parameters(arity = "0..*", paramLabel = "PROMPT",
            description = "Optional initial prompt. All trailing args are joined with single spaces and submitted as the first user turn.")
    List<String> initialPromptArgs;

    @Override
    public Integer call() {
        String resolvedKey;
        try {
            resolvedKey = resolveApiKey();
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
            return 2;
        }

        String initialPrompt = (initialPromptArgs == null || initialPromptArgs.isEmpty())
                ? ""
                : String.join(" ", initialPromptArgs).trim();
        if (batch && initialPrompt.isEmpty()) {
            System.err.println("--batch requires a non-empty PROMPT");
            return 2;
        }

        boolean toolsOn = resolveToolsEnabled();

        AzureOpenAIClient client = new AzureOpenAIClient(endpoint, resolvedKey, deployment, apiVersion);

        ToolRegistry registry = new ToolRegistry();
        ToolDispatcher dispatcher = null;
        if (toolsOn) {
            registry.register(new ReadFileTool(toolRoot));
            dispatcher = new ToolDispatcher(registry);
        }

        ChatSession session = new ChatSession(client, SystemPrompt.load(), registry, dispatcher);

        printBanner(resolvedKey, toolsOn, registry);

        if (!initialPrompt.isEmpty()) {
            if (!batch) {
                System.out.println("> " + initialPrompt);
            }
            try {
                streamOneTurn(session, initialPrompt);
            } catch (RuntimeException e) {
                System.err.println("error talking to Azure OpenAI: " + e.getMessage());
                return 1;
            }
            if (batch) {
                return 0;
            }
        }

        runRepl(session);
        System.out.println("hej då!");
        return 0;
    }

    private boolean resolveToolsEnabled() {
        if (toolsFlag != null) {
            return toolsFlag;
        }
        String env = System.getenv("CHATBOT_TOOLS");
        if (env == null || env.isBlank()) {
            return true;
        }
        return switch (env.trim().toLowerCase()) {
            case "off", "false", "0", "no" -> false;
            default -> true;
        };
    }

    private String resolveApiKey() {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.trim();
        }
        if (apiKeyFile != null) {
            try {
                String fromFile = Files.readString(apiKeyFile).trim();
                if (!fromFile.isBlank()) {
                    return fromFile;
                }
            } catch (IOException e) {
                throw new IllegalStateException("reading " + apiKeyFile + ": " + e.getMessage(), e);
            }
            throw new IllegalStateException("API key file is empty: " + apiKeyFile);
        }
        if (Files.isRegularFile(API_KEY_FILE)) {
            try {
                String fromFile = Files.readString(API_KEY_FILE).trim();
                if (!fromFile.isBlank()) {
                    return fromFile;
                }
            } catch (IOException e) {
                throw new IllegalStateException("reading " + API_KEY_FILE + ": " + e.getMessage(), e);
            }
        }
        throw new IllegalStateException(
                "no API key. Use --api-key, --api-key-file, set AZURE_OPENAI_API_KEY, "
                        + "or put the key in " + API_KEY_FILE.toAbsolutePath());
    }

    private void printBanner(String key, boolean toolsOn, ToolRegistry registry) {
        System.err.println("hello-ms-foundry — chatbot ready");
        System.err.println("  deployment:  " + deployment);
        System.err.println("  api-version: " + apiVersion);
        System.err.println("  api-key:     " + maskKey(key));
        if (toolsOn) {
            ReadFileTool readFile = (ReadFileTool) registry.get("read_file");
            System.err.println("  tools:       read_file (sandbox=" + readFile.root() + ")");
        } else {
            System.err.println("  tools:       off");
        }
        if (!batch) {
            System.err.println("Commands: :exit / :quit (also Ctrl-D), :reset");
        }
        System.err.println();
    }

    private void streamOneTurn(ChatSession session, String prompt) {
        session.streamReply(prompt, chunk -> {
            System.out.print(chunk);
            System.out.flush();
        });
        System.out.println();
        if (!batch) {
            System.out.println();
        }
    }

    private void runRepl(ChatSession session) {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("> ");
            System.out.flush();

            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                System.err.println("input error: " + e.getMessage());
                return;
            }
            if (line == null) {
                System.out.println();
                return;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.equals(":exit") || trimmed.equals(":quit")) {
                return;
            }
            if (trimmed.equals(":reset")) {
                session.reset();
                System.out.println("(history cleared)");
                System.out.println();
                continue;
            }

            try {
                streamOneTurn(session, trimmed);
            } catch (RuntimeException e) {
                System.err.println();
                System.err.println("error talking to Azure OpenAI: " + e.getMessage());
                System.err.println();
            }
        }
    }

    private static String maskKey(String key) {
        if (key.length() <= 8) {
            return "*".repeat(key.length());
        }
        return key.substring(0, 4) + "…" + key.substring(key.length() - 4)
                + " (" + key.length() + " chars)";
    }
}
