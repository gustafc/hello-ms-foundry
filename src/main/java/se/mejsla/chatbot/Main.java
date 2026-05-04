package se.mejsla.chatbot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {

    private static final String DEFAULT_ENDPOINT =
            "https://bybrick-tech-openai-play.openai.azure.com/";
    private static final String DEFAULT_DEPLOYMENT = "gpt-5.2-chat";
    private static final String DEFAULT_API_VERSION = "2025-01-01-preview";
    private static final Path API_KEY_FILE = Path.of("apikey.txt");

    private Main() {
    }

    public static void main(String[] args) {
        Config config = Config.load();

        AzureOpenAIClient client = new AzureOpenAIClient(
                config.endpoint(), config.apiKey(), config.deployment(), config.apiVersion());
        ChatSession session = new ChatSession(client, SystemPrompt.load());

        System.out.println("hello-ms-foundry — chatbot ready");
        System.out.println("  deployment:  " + config.deployment());
        System.out.println("  api-version: " + config.apiVersion());
        System.out.println("  api-key:     " + maskKey(config.apiKey()));
        System.out.println("Commands: :exit / :quit (also Ctrl-D), :reset");
        System.out.println();

        runRepl(session);
        System.out.println("hej då!");
    }

    private static void runRepl(ChatSession session) {
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
                session.streamReply(trimmed, chunk -> {
                    System.out.print(chunk);
                    System.out.flush();
                });
                System.out.println();
                System.out.println();
            } catch (RuntimeException e) {
                System.err.println();
                System.err.println("error talking to Azure OpenAI: " + e.getMessage());
                System.err.println();
            }
        }
    }

    record Config(String apiKey, String endpoint, String deployment, String apiVersion) {

        static Config load() {
            return new Config(
                    resolveApiKey(),
                    envOr("AZURE_OPENAI_ENDPOINT", DEFAULT_ENDPOINT),
                    envOr("AZURE_OPENAI_DEPLOYMENT", DEFAULT_DEPLOYMENT),
                    envOr("AZURE_OPENAI_API_VERSION", DEFAULT_API_VERSION));
        }

        private static String resolveApiKey() {
            String fromEnv = System.getenv("AZURE_OPENAI_API_KEY");
            if (fromEnv != null && !fromEnv.isBlank()) {
                return fromEnv.trim();
            }
            if (Files.isRegularFile(API_KEY_FILE)) {
                try {
                    String fromFile = Files.readString(API_KEY_FILE).trim();
                    if (!fromFile.isBlank()) {
                        return fromFile;
                    }
                } catch (IOException e) {
                    throw new ConfigException("reading " + API_KEY_FILE, e);
                }
            }
            throw new ConfigException(
                    "no API key. Set AZURE_OPENAI_API_KEY or put the key in "
                            + API_KEY_FILE.toAbsolutePath());
        }

        private static String envOr(String name, String fallback) {
            String value = System.getenv(name);
            return (value == null || value.isBlank()) ? fallback : value.trim();
        }
    }

    static final class ConfigException extends RuntimeException {
        ConfigException(String message) {
            super(message);
        }
        ConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static String maskKey(String key) {
        if (key.length() <= 8) {
            return "*".repeat(key.length());
        }
        return key.substring(0, 4) + "…" + key.substring(key.length() - 4) + " (" + key.length() + " chars)";
    }
}
