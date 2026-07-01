# Spring Boot RAG Notes

Spring Boot can be combined with LangChain4j to build a small RAG service.

The typical flow is:

1. Load local documents.
2. Split them into smaller text segments.
3. Convert each segment into embeddings.
4. Store embeddings in an embedding store.
5. Retrieve the most relevant segments for a user question.
6. Send the retrieved context to the chat model.

For LangChain4j, a common minimal setup uses:

- `FileSystemDocumentLoader`
- `DocumentSplitters.recursive(...)`
- `OpenAiEmbeddingModel`
- `InMemoryEmbeddingStore`
- `EmbeddingStoreContentRetriever`

If the knowledge base does not contain the answer, the assistant should say so directly.
