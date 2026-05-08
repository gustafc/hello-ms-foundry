package se.mejsla.chatbot;

import com.azure.ai.openai.models.ChatCompletionsToolCall;
import com.azure.ai.openai.models.ChatCompletionsToolDefinition;
import com.azure.ai.openai.models.ChatRequestAssistantMessage;
import com.azure.ai.openai.models.ChatRequestMessage;
import com.azure.ai.openai.models.ChatRequestSystemMessage;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import com.azure.ai.openai.models.ChatResponseMessage;
import se.mejsla.chatbot.tools.ToolDispatcher;
import se.mejsla.chatbot.tools.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ChatSession {

    static final int MAX_TOOL_ITERATIONS = 4;

    private final AzureOpenAIClient client;
    private final String systemPrompt;
    private final ToolRegistry registry;
    private final ToolDispatcher dispatcher;
    private final List<ChatRequestMessage> history = new ArrayList<>();

    public ChatSession(AzureOpenAIClient client, String systemPrompt) {
        this(client, systemPrompt, new ToolRegistry(), null);
    }

    public ChatSession(
            AzureOpenAIClient client,
            String systemPrompt,
            ToolRegistry registry,
            ToolDispatcher dispatcher) {
        this.client = client;
        this.systemPrompt = systemPrompt;
        this.registry = registry;
        this.dispatcher = dispatcher;
        reset();
    }

    public void reset() {
        history.clear();
        history.add(new ChatRequestSystemMessage(systemPrompt));
    }

    public void streamReply(String userMessage, Consumer<String> chunkSink) {
        history.add(new ChatRequestUserMessage(userMessage));

        if (registry.isEmpty() || dispatcher == null) {
            streamWithoutTools(chunkSink);
            return;
        }
        runToolLoop(chunkSink);
    }

    private void streamWithoutTools(Consumer<String> chunkSink) {
        StringBuilder full = new StringBuilder();
        client.streamChat(history, chunk -> {
            full.append(chunk);
            chunkSink.accept(chunk);
        });
        history.add(new ChatRequestAssistantMessage(full.toString()));
    }

    private void runToolLoop(Consumer<String> chunkSink) {
        List<ChatCompletionsToolDefinition> tools = registry.definitions();
        for (int i = 0; i < MAX_TOOL_ITERATIONS; i++) {
            ChatResponseMessage msg = client.chatWithTools(history, tools);
            List<ChatCompletionsToolCall> calls = msg.getToolCalls();
            String content = msg.getContent() == null ? "" : msg.getContent();

            if (calls == null || calls.isEmpty()) {
                history.add(new ChatRequestAssistantMessage(content));
                chunkSink.accept(content);
                return;
            }

            ChatRequestAssistantMessage assistant = new ChatRequestAssistantMessage(content);
            assistant.setToolCalls(calls);
            history.add(assistant);

            for (ChatCompletionsToolCall call : calls) {
                history.add(dispatcher.dispatch(call));
            }
        }

        String fallback = "(stopped after " + MAX_TOOL_ITERATIONS + " tool iterations)";
        history.add(new ChatRequestAssistantMessage(fallback));
        chunkSink.accept(fallback);
    }
}
