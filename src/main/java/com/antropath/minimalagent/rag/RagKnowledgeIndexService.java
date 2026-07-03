package com.antropath.minimalagent.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RagKnowledgeIndexService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RagKnowledgeIndexService.class);

    private static final String SOURCE_PATH_KEY = "source_path";
    private static final String CONTENT_HASH_KEY = "content_hash";

    private final Path knowledgePath;
    private final PgVectorEmbeddingStore embeddingStore;
    private final RagKnowledgeDocumentRepository repository;
    private final EmbeddingStoreIngestor ingestor;

    public RagKnowledgeIndexService(@Value("${rag.knowledge-path:knowledge}") String knowledgePath,
                                    EmbeddingModel embeddingModel,
                                    PgVectorEmbeddingStore embeddingStore,
                                    RagKnowledgeDocumentRepository repository) {
        this.knowledgePath = Path.of(knowledgePath);
        this.embeddingStore = embeddingStore;
        this.repository = repository;
        this.ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(700, 100))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();
    }

    @Override
    public void run(ApplicationArguments args) {
        synchronizeKnowledgeBase();
    }

    public void synchronizeKnowledgeBase() {
        if (!Files.exists(knowledgePath)) {
            log.warn("Knowledge path does not exist: {}", knowledgePath.toAbsolutePath());
            cleanupAllIndexedDocuments();
            return;
        }

        List<Document> rawDocuments = FileSystemDocumentLoader.loadDocumentsRecursively(knowledgePath);
        if (rawDocuments.isEmpty()) {
            log.info("No knowledge documents found under {}", knowledgePath.toAbsolutePath());
            cleanupAllIndexedDocuments();
            return;
        }

        Map<String, Document> currentDocuments = rawDocuments.stream()
                .map(this::enrichWithSourcePath)
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        document -> document.metadata().getString(SOURCE_PATH_KEY),
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new));

        Map<String, RagKnowledgeDocument> existingByPath = repository.findAll().stream()
                .collect(Collectors.toMap(RagKnowledgeDocument::getSourcePath, Function.identity(), (left, right) -> left, LinkedHashMap::new));

        Set<String> currentPaths = currentDocuments.keySet();
        List<String> stalePaths = existingByPath.keySet().stream()
                .filter(path -> !currentPaths.contains(path))
                .sorted()
                .toList();
        removeStaleDocuments(stalePaths);

        List<String> processed = new ArrayList<>();
        for (Map.Entry<String, Document> entry : currentDocuments.entrySet()) {
            String sourcePath = entry.getKey();
            Document document = entry.getValue();
            String contentHash = hash(document.text());
            RagKnowledgeDocument existing = existingByPath.get(sourcePath);
            if (existing != null && contentHash.equals(existing.getContentHash())) {
                continue;
            }
            if (existing != null) {
                removeVectorsForSource(sourcePath);
            }
            ingestDocument(document, sourcePath, contentHash);
            processed.add(sourcePath);
        }

        if (processed.isEmpty() && stalePaths.isEmpty()) {
            log.info("RAG knowledge base is already up to date. documents={}", currentDocuments.size());
        } else {
            log.info("RAG knowledge base synchronized. updated={}, removed={}, total={}",
                    processed.size(), stalePaths.size(), currentDocuments.size());
        }
    }

    private void cleanupAllIndexedDocuments() {
        List<RagKnowledgeDocument> indexedDocuments = repository.findAll();
        if (indexedDocuments.isEmpty()) {
            return;
        }
        for (RagKnowledgeDocument document : indexedDocuments) {
            removeVectorsForSource(document.getSourcePath());
        }
        repository.deleteAllInBatch(indexedDocuments);
        log.info("Removed all indexed knowledge documents because source directory is empty.");
    }

    private void removeStaleDocuments(List<String> stalePaths) {
        for (String stalePath : stalePaths) {
            removeVectorsForSource(stalePath);
            repository.deleteBySourcePath(stalePath);
            log.info("Removed stale knowledge document: {}", stalePath);
        }
    }

    private void ingestDocument(Document document, String sourcePath, String contentHash) {
        Document enriched = enrichDocument(document, sourcePath, contentHash);
        ingestor.ingest(enriched);

        RagKnowledgeDocument record = repository.findBySourcePath(sourcePath)
                .orElseGet(() -> new RagKnowledgeDocument(sourcePath, contentHash));
        record.updateContentHash(contentHash);
        repository.save(record);

        log.info("Indexed knowledge document: {}", sourcePath);
    }

    private void removeVectorsForSource(String sourcePath) {
        embeddingStore.removeAll(MetadataFilterBuilder.metadataKey(SOURCE_PATH_KEY).isEqualTo(sourcePath));
    }

    private Document enrichWithSourcePath(Document document) {
        String sourcePath = resolveSourcePath(document);
        if (sourcePath == null || sourcePath.isBlank()) {
            log.warn("Skip document without source path metadata: {}", document.metadata());
            return null;
        }
        Metadata metadata = document.metadata().copy()
                .put(SOURCE_PATH_KEY, sourcePath);
        return Document.document(document.text(), metadata);
    }

    private Document enrichDocument(Document document, String sourcePath, String contentHash) {
        Metadata metadata = document.metadata().copy()
                .put(SOURCE_PATH_KEY, sourcePath)
                .put(CONTENT_HASH_KEY, contentHash);
        return Document.document(document.text(), metadata);
    }

    private String resolveSourcePath(Document document) {
        Metadata metadata = document.metadata();
        String absoluteDirectoryPath = metadata.getString(Document.ABSOLUTE_DIRECTORY_PATH);
        String fileName = metadata.getString(Document.FILE_NAME);
        if (absoluteDirectoryPath != null && !absoluteDirectoryPath.isBlank()
                && fileName != null && !fileName.isBlank()) {
            return Path.of(absoluteDirectoryPath, fileName).toAbsolutePath().normalize().toString();
        }
        String url = metadata.getString(Document.URL);
        if (url != null && !url.isBlank()) {
            return url;
        }
        return fileName;
    }

    private static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available", exception);
        }
    }
}
