package se.mejsla.chatbot;

import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.OpenAIServiceVersion;
import com.azure.ai.openai.models.ChatChoice;
import com.azure.ai.openai.models.ChatCompletions;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatRequestMessage;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import com.azure.ai.openai.models.ChatResponseMessage;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.util.IterableStream;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public final class AzureOpenAIClient {

    private final OpenAIClient client;
    private final String deployment;

    public AzureOpenAIClient(String endpoint, String apiKey, String deployment, String apiVersion) {
        this.client = new OpenAIClientBuilder()
                .endpoint(endpoint)
                .credential(new AzureKeyCredential(apiKey))
                .serviceVersion(resolveServiceVersion(apiVersion))
                .buildClient();
        this.deployment = deployment;
    }

    public String chat(List<ChatRequestMessage> messages) {
        ChatCompletions completions = client.getChatCompletions(deployment, new ChatCompletionsOptions(messages));
        List<ChatChoice> choices = completions.getChoices();
        if (choices.isEmpty()) {
            throw new IllegalStateException("Azure OpenAI returned no choices");
        }
        return choices.get(0).getMessage().getContent();
    }

    public String ask(String userMessage) {
        return chat(List.of(new ChatRequestUserMessage(userMessage)));
    }

    public void streamChat(List<ChatRequestMessage> messages, Consumer<String> chunkSink) {
        IterableStream<ChatCompletions> stream =
                client.getChatCompletionsStream(deployment, new ChatCompletionsOptions(messages));
        for (ChatCompletions completions : stream) {
            for (ChatChoice choice : completions.getChoices()) {
                ChatResponseMessage delta = choice.getDelta();
                if (delta == null) {
                    continue;
                }
                String content = delta.getContent();
                if (content != null && !content.isEmpty()) {
                    chunkSink.accept(content);
                }
            }
        }
    }

    private static OpenAIServiceVersion resolveServiceVersion(String requested) {
        for (OpenAIServiceVersion v : OpenAIServiceVersion.values()) {
            if (v.getVersion().equals(requested)) {
                return v;
            }
        }
        OpenAIServiceVersion latest = Arrays.stream(OpenAIServiceVersion.values())
                .reduce((a, b) -> b)
                .orElseThrow();
        System.err.println("warn: api-version '" + requested
                + "' not in SDK enum; falling back to " + latest.getVersion());
        return latest;
    }
}
