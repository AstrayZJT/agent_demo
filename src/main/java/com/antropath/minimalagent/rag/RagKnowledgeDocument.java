package com.antropath.minimalagent.rag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "rag_knowledge_document")
public class RagKnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 512)
    private String sourcePath;

    @Column(nullable = false, length = 64)
    private String contentHash;

    @Column(nullable = false)
    private LocalDateTime indexedAt;

    protected RagKnowledgeDocument() {
    }

    public RagKnowledgeDocument(String sourcePath, String contentHash) {
        this.sourcePath = sourcePath;
        this.contentHash = contentHash;
    }

    @PrePersist
    @PreUpdate
    public void touch() {
        indexedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public String getContentHash() {
        return contentHash;
    }

    public LocalDateTime getIndexedAt() {
        return indexedAt;
    }

    public void updateContentHash(String contentHash) {
        this.contentHash = contentHash;
    }
}
