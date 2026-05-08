package se.mejsla.chatbot.tools;

import com.azure.ai.openai.models.ChatCompletionsFunctionToolDefinition;
import com.azure.ai.openai.models.ChatCompletionsFunctionToolDefinitionFunction;
import com.azure.ai.openai.models.ChatCompletionsToolDefinition;
import com.azure.core.util.BinaryData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry register(Tool tool) {
        tools.put(tool.name(), tool);
        return this;
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }

    public List<ChatCompletionsToolDefinition> definitions() {
        return tools.values().stream().<ChatCompletionsToolDefinition>map(tool -> {
            ChatCompletionsFunctionToolDefinitionFunction fn =
                    new ChatCompletionsFunctionToolDefinitionFunction(tool.name())
                            .setDescription(tool.description())
                            .setParameters(BinaryData.fromString(tool.parametersJsonSchema()));
            return new ChatCompletionsFunctionToolDefinition(fn);
        }).toList();
    }
}
