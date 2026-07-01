# LangChain4j Notes

LangChain4j is a Java library for building LLM applications.

In a RAG workflow, the embedding model should be used consistently for both:

- indexing documents
- embedding user queries

The vector store does not answer questions by itself.
It only helps find relevant text segments.

The chat model then uses those retrieved segments to produce the final response.
