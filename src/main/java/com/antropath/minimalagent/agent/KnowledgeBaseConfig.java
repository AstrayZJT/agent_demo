package com.antropath.minimalagent.agent;

import com.antropath.minimalagent.memory.ConversationMemoryService;
import com.antropath.minimalagent.guardrail.PromptInjectionInputGuardrail;
import com.antropath.minimalagent.guardrail.ResponseSanityOutputGuardrail;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.guardrail.config.OutputGuardrailsConfig;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Configuration
public class KnowledgeBaseConfig {

    @Value("${rag.knowledge-path:knowledge}")
    private String knowledgePath;

    @Value("${langchain4j.open-ai.chat-model.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String chatBaseUrl;

    @Value("${langchain4j.open-ai.chat-model.api-key:${OPENAI_API_KEY:}}")
    private String chatApiKey;

    @Value("${langchain4j.open-ai.chat-model.model-name:qwen-plus}")
    private String chatModelName;

    @Value("${langchain4j.open-ai.embedding-model.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String embeddingBaseUrl;

    @Value("${langchain4j.open-ai.embedding-model.api-key:${OPENAI_API_KEY:}}")
    private String embeddingApiKey;

    @Value("${langchain4j.open-ai.embedding-model.model-name:text-embedding-v4}")
    private String embeddingModelName;

    @Bean
    public OpenAiChatModel chatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(chatBaseUrl)
                .apiKey(chatApiKey)
                .modelName(chatModelName)
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(embeddingBaseUrl)
                .apiKey(embeddingApiKey)
                .modelName(embeddingModelName)
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    @Bean
    public InMemoryEmbeddingStore<TextSegment> embeddingStore(EmbeddingModel embeddingModel) {
        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        Path path = Path.of(knowledgePath);
        if (Files.exists(path)) {
            List<Document> documents = FileSystemDocumentLoader.loadDocumentsRecursively(path);
            if (!documents.isEmpty()) {
                EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                        .documentSplitter(DocumentSplitters.recursive(700, 100))
                        .embeddingModel(embeddingModel)
                        .embeddingStore(store)
                        .build();
                ingestor.ingest(documents);
            }
        }
        return store;
    }

    @Bean
    public ContentRetriever knowledgeContentRetriever(EmbeddingModel embeddingModel,
                                                      InMemoryEmbeddingStore<TextSegment> embeddingStore) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(4)
                .minScore(0.55)
                .build();
    }

    @Bean
    public OutputGuardrailsConfig outputGuardrailsConfig() {
        return OutputGuardrailsConfig.builder()
                .maxRetries(1)
                .build();
    }

    @Bean
    public ChatMemoryProvider chatMemoryProvider() {
        return memoryId -> "-1".equals(String.valueOf(memoryId))
                ? new EphemeralChatMemory(memoryId)
                : MessageWindowChatMemory.withMaxMessages(12);
    }

    @Bean("ragAssistant")
    public Assistant ragAssistant(OpenAiChatModel chatModel,
                                  ContentRetriever knowledgeContentRetriever,
                                  ChatMemoryProvider chatMemoryProvider,
                                  PromptInjectionInputGuardrail promptInjectionInputGuardrail,
                                  ResponseSanityOutputGuardrail responseSanityOutputGuardrail,
                                  OutputGuardrailsConfig outputGuardrailsConfig,
                                  ConversationMemoryService conversationMemoryService) {
        return AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .contentRetriever(knowledgeContentRetriever)
                .inputGuardrails(promptInjectionInputGuardrail)
                .outputGuardrails(responseSanityOutputGuardrail)
                .outputGuardrailsConfig(outputGuardrailsConfig)
                .systemMessageProvider(userId -> buildRagSystemMessage((String) userId, conversationMemoryService))
                .build();
    }

    @Bean("toolAssistant")
    public Assistant toolAssistant(OpenAiChatModel chatModel,
                                   MinimalAgentTools minimalAgentTools,
                                   ChatMemoryProvider chatMemoryProvider,
                                   PromptInjectionInputGuardrail promptInjectionInputGuardrail,
                                   ResponseSanityOutputGuardrail responseSanityOutputGuardrail,
                                   OutputGuardrailsConfig outputGuardrailsConfig,
                                   ConversationMemoryService conversationMemoryService) {
        return AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(minimalAgentTools)
                .inputGuardrails(promptInjectionInputGuardrail)
                .outputGuardrails(responseSanityOutputGuardrail)
                .outputGuardrailsConfig(outputGuardrailsConfig)
                .systemMessageProvider(userId -> buildToolSystemMessage((String) userId, conversationMemoryService))
                .build();
    }

    private String buildRagSystemMessage(String userId, ConversationMemoryService conversationMemoryService) {
        String memoryContext = conversationMemoryService.buildMemoryContext(userId);
        return """
                你是一个中文学习助手，当前工作模式是知识库问答。
                1. 优先根据检索到的知识库内容回答。
                2. 如果知识库没有相关信息，直接说明没有找到相关资料，不要编造。
                3. 同一个 userId 的历史对话记忆如下：
                %s
                4. 回答要准确、自然、简洁。
                """.formatted(memoryContext);
    }

    private String buildToolSystemMessage(String userId, ConversationMemoryService conversationMemoryService) {
        String memoryContext = conversationMemoryService.buildMemoryContext(userId);
        return """
                你是一个中文学习助手，当前工作模式是工具调用。
                1. 只要问题涉及事实查询、联网搜索、网页内容总结，就必须先调用工具，不能直接凭记忆回答。
                2. 优先使用 webSearch；如果拿到结果后还需要网页正文，再调用 visitWebpage。
                3. 如果工具结果不足以支持结论，要明确说明，不要编造。
                3. 同一个 userId 的历史对话记忆如下：
                %s
                4. 回答要准确、自然、简洁。
                """.formatted(memoryContext);
    }

    private static final class EphemeralChatMemory implements ChatMemory {

        private final Object id;
        private final List<ChatMessage> messages = new CopyOnWriteArrayList<>();

        private EphemeralChatMemory(Object id) {
            this.id = id;
        }

        @Override
        public Object id() {
            return id;
        }

        @Override
        public void add(ChatMessage message) {
            messages.add(message);
        }

        @Override
        public List<ChatMessage> messages() {
            return List.copyOf(messages);
        }

        @Override
        public void clear() {
            messages.clear();
        }
    }
}
