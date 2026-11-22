package io.crawlj4ai.extraction;

import dev.langchain4j.model.chat.ChatModel;

/**
 * Schema-guided LLM extraction strategy operating directly on {@code fitMarkdown}.
 */
public class LlmExtractionStrategy implements ExtractionStrategy {

    private final ChatModel chatModel;
    private final String instruction;
    private final String jsonSchemaExample;

    public LlmExtractionStrategy(ChatModel chatModel, String instruction, String jsonSchemaExample) {
        this.chatModel = chatModel;
        this.instruction = instruction;
        this.jsonSchemaExample = jsonSchemaExample;
    }

    @Override
    public String extract(String url, String cleanedHtml, String fitMarkdown) {
        String prompt = """
                Extract structured data from the following Markdown webpage content.
                Return ONLY valid JSON matching the target schema—no markdown fences or commentary.

                Source URL: %s
                Instruction: %s
                Target JSON Schema:
                %s

                Content:
                %s
                """.formatted(url, instruction, jsonSchemaExample, fitMarkdown);

        return chatModel.chat(prompt).trim();
    }
}
