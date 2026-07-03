package com.antropath.minimalagent.rag;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RagKnowledgeDocumentRepository extends JpaRepository<RagKnowledgeDocument, Long> {

    Optional<RagKnowledgeDocument> findBySourcePath(String sourcePath);

    List<RagKnowledgeDocument> findBySourcePathIn(Collection<String> sourcePaths);

    void deleteBySourcePath(String sourcePath);
}
