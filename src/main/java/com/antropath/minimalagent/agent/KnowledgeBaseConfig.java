package com.antropath.minimalagent.agent;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
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
    public Assistant assistant(ContentRetriever knowledgeContentRetriever) {
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
                .build();
    }
}
