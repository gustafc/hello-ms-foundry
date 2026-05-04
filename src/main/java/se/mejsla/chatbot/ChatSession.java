package se.mejsla.chatbot;

import com.azure.ai.openai.models.ChatRequestAssistantMessage;
import com.azure.ai.openai.models.ChatRequestMessage;
import com.azure.ai.openai.models.ChatRequestSystemMessage;
import com.azure.ai.openai.models.ChatRequestUserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ChatSession {

    private final AzureOpenAIClient client;
    private final String systemPrompt;
    private final List<ChatRequestMessage> history = new ArrayList<>();

    public ChatSession(AzureOpenAIClient client, String systemPrompt) {
        this.client = client;
        this.systemPrompt = systemPrompt;
        reset();
    }

    public void reset() {
        history.clear();
        history.add(new ChatRequestSystemMessage(systemPrompt));
    }

    public void streamReply(String userMessage, Consumer<String> chunkSink) {
        history.add(new ChatRequestUserMessage(userMessage));
        StringBuilder full = new StringBuilder();
        client.streamChat(history, chunk -> {
            full.append(chunk);
            chunkSink.accept(chunk);
        });
        history.add(new ChatRequestAssistantMessage(full.toString()));
    }
}
