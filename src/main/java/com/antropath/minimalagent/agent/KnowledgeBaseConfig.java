package com.antropath.minimalagent.agent;

import com.antropath.minimalagent.api.AgentRequest;
import com.antropath.minimalagent.memory.ConversationMemoryService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
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

    @Value("${langchain4j.open-ai.embedding-model.model-name:text-embedding-3-small}")
    private String embeddingModelName;

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
    public Assistant assistant(ContentRetriever knowledgeContentRetriever,
                                ConversationMemoryService conversationMemoryService) {
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .baseUrl(chatBaseUrl)
                .apiKey(chatApiKey)
                .modelName(chatModelName)
                .logRequests(true)
                .logResponses(true)
                .build();

        return AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .contentRetriever(knowledgeContentRetriever)
                .systemMessageProviderWithContext(context -> {
                    AgentRequest request = extractRequest(context);
                    String memoryContext = conversationMemoryService.buildMemoryContext(request.userId());
                    return """
                            你是一个中文学习助手，同时支持知识库问答和用户记忆。
                            1. 优先根据检索到的知识库内容回答。
                            2. 同一个 userId 的历史对话记忆如下：
                            %s
                            3. 如果知识库中没有相关资料，请明确说明。
                            4. 回答尽量准确、自然、简洁。
                            """.formatted(memoryContext);
                })
                .userMessageProvider(input -> {
                    if (input instanceof AgentRequest request) {
                        return request.task();
                    }
                    if (input instanceof String task) {
                        return task;
                    }
                    return String.valueOf(input);
                })
                .build();
    }

    private static AgentRequest extractRequest(InvocationContext context) {
        if (context != null && !context.methodArguments().isEmpty()) {
            Object argument = context.methodArguments().get(0);
            if (argument instanceof AgentRequest request) {
                return request;
            }
        }
        throw new IllegalStateException("Unable to resolve AgentRequest from invocation context");
    }
}
