package se.mejsla.chatbot.tools;

import com.azure.ai.openai.models.ChatCompletionsFunctionToolCall;
import com.azure.ai.openai.models.ChatCompletionsToolCall;
import com.azure.ai.openai.models.ChatRequestToolMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class ToolDispatcher {

    private final ToolRegistry registry;
    private final ObjectMapper json = new ObjectMapper();

    public ToolDispatcher(ToolRegistry registry) {
        this.registry = registry;
    }

    public ChatRequestToolMessage dispatch(ChatCompletionsToolCall call) {
        if (!(call instanceof ChatCompletionsFunctionToolCall fnCall)) {
            return new ChatRequestToolMessage(
                    "error: unsupported tool-call type", call.getId());
        }
        String name = fnCall.getFunction().getName();
        String rawArgs = fnCall.getFunction().getArguments();
        Tool tool = registry.get(name);
        if (tool == null) {
            return new ChatRequestToolMessage(
                    "error: unknown tool '" + name + "'", call.getId());
        }
        JsonNode args;
        try {
            args = (rawArgs == null || rawArgs.isBlank())
                    ? json.createObjectNode()
                    : json.readTree(rawArgs);
        } catch (JsonProcessingException e) {
            return new ChatRequestToolMessage(
                    "error: arguments not valid JSON: " + e.getOriginalMessage(),
                    call.getId());
        }
        long start = System.nanoTime();
        String result;
        try {
            result = tool.execute(args);
        } catch (RuntimeException e) {
            result = "error: tool failed: " + e.getMessage();
        }
        long ms = (System.nanoTime() - start) / 1_000_000L;
        System.err.println("[tool] " + name + " args=" + compact(rawArgs)
                + " bytes=" + result.length() + " " + ms + "ms");
        return new ChatRequestToolMessage(result, call.getId());
    }

    private static String compact(String s) {
        if (s == null) {
            return "{}";
        }
        String trimmed = s.replaceAll("\\s+", " ").trim();
        return trimmed.length() > 120 ? trimmed.substring(0, 117) + "..." : trimmed;
    }
}
