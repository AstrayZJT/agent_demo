# 今日 RAG 核心问题总结

本文整理今天围绕 RAG、向量入库、向量查询、提示词拼接、LangChain4j 内部自动流程的几个关键问题。  
用途是帮助后续对照代码阅读，分清楚：哪些代码是项目自己写的，哪些能力是 LangChain4j 框架内部提供的。

## 1. `ingestor.ingest(enriched)` 为什么不用再指定文件路径和数据库表

问题：

```java
ingestor.ingest(enriched);
```

这一行执行入库时，为什么不用再传文件路径、数据库地址、表名？

结论：

因为这些信息在执行前已经分别放进了两个对象里：

```text
ingestor = 切片规则 + embedding 模型 + 向量库对象
enriched = 文档正文 + source_path + content_hash
```

`ingestor` 在 `RagKnowledgeIndexService` 构造方法里创建：

```java
this.ingestor = EmbeddingStoreIngestor.builder()
        .documentSplitter(DocumentSplitters.recursive(700, 100))
        .embeddingModel(embeddingModel)
        .embeddingStore(embeddingStore)
        .build();
```

这里已经指定：

- 用什么规则切片：`DocumentSplitters.recursive(700, 100)`
- 用什么模型向量化：`embeddingModel`
- 存到哪里：`embeddingStore`

而 `embeddingStore` 是在 `KnowledgeBaseConfig` 里配置好的：

```java
PgVectorEmbeddingStore.builder()
        .host(pgvectorHost)
        .port(pgvectorPort)
        .database(pgvectorDatabase)
        .user(pgvectorUsername)
        .password(pgvectorPassword)
        .table(pgvectorTable)
        .dimension(embeddingModel.dimension())
        .createTable(true)
        .build();
```

所以数据库位置和表名不是在 `ingest(...)` 这一行传的，而是已经封装在 `embeddingStore` 里。

文件路径也不是在 `ingest(...)` 这一行单独传的，而是提前放进了 `Document` 的 metadata：

```java
Document enriched = enrichDocument(document, sourcePath, contentHash);
```

里面会写入：

```java
Metadata metadata = document.metadata().copy()
        .put(SOURCE_PATH_KEY, sourcePath)
        .put(CONTENT_HASH_KEY, contentHash);
```

所以 `ingestor.ingest(enriched)` 内部就能拿到：

- 文档正文
- 文件来源 `source_path`
- 文件内容 hash `content_hash`
- 切片规则
- embedding 模型
- pgvector 存储位置

## 2. hash 是按整个文件算，还是按每个分片算

问题：

既然后面会切片，那么判断文件变化时，是不是每个分片单独 hash？

结论：

当前项目是**文件级 hash**，不是分片级 hash。

对应代码：

```java
String contentHash = hash(document.text());
```

这里的 `document.text()` 是整个文档内容。  
这个判断发生在切片之前。

流程是：

```text
读取整个文件
  -> 对整个文件正文计算 SHA-256 hash
  -> 和 rag_knowledge_document 表里的旧 hash 对比
  -> 没变化：跳过
  -> 有变化：删除这个文件旧向量，重新切片、向量化、入库
```

所以当前项目不是：

```text
切片后每个 chunk 单独算 hash
```

而是：

```text
一个文件一个 hash
```

优点：

- 简单
- 容易理解
- 很适合学习项目和小型知识库

缺点：

- 如果大文件只改一句话，也会重新处理整个文件

企业级复杂方案可能会做分片级 hash，但需要额外维护：

- `chunkId`
- `chunkIndex`
- `chunkHash`
- `sourcePath`
- 文档版本
- 删除分片的追踪逻辑

## 3. Bean 是怎么注入到 `rag` 包里的

问题：

`PgVectorEmbeddingStore` 明明是在 `KnowledgeBaseConfig` 里定义的 Bean，为什么 `RagKnowledgeIndexService` 里可以直接用？

结论：

它是通过 Spring 的**构造器注入**传进去的，不一定非要写字段上的 `@Autowired`。

`KnowledgeBaseConfig` 定义 Bean：

```java
@Bean
public PgVectorEmbeddingStore embeddingStore(EmbeddingModel embeddingModel) {
    return PgVectorEmbeddingStore.builder()
            ...
            .build();
}
```

`RagKnowledgeIndexService` 构造方法接收：

```java
public RagKnowledgeIndexService(@Value("${rag.knowledge-path:knowledge}") String knowledgePath,
                                EmbeddingModel embeddingModel,
                                PgVectorEmbeddingStore embeddingStore,
                                RagKnowledgeDocumentRepository repository) {
```

Spring 启动时会发现：

```text
RagKnowledgeIndexService 需要 PgVectorEmbeddingStore
```

然后从容器里找到 `KnowledgeBaseConfig` 创建的那个 Bean，自动传进来。

同理：

- `EmbeddingModel` 也是 Bean
- `RagKnowledgeDocumentRepository` 也是 Spring Data JPA 创建的 Bean

## 4. `rag` 包负责什么

问题：

`rag` 包是不是同时负责查询和写入？

更准确的说法是：

**`rag` 包主要负责知识库文件同步和向量入库，不负责用户提问时的自动查询拼接。**

`rag` 包里的核心类：

```text
RagKnowledgeIndexService
  -> 启动时扫描 knowledge 目录
  -> 读取文件
  -> 判断 hash 是否变化
  -> 删除过期向量
  -> 调用 ingestor.ingest(enriched)
  -> 维护 rag_knowledge_document 表

RagKnowledgeDocument
  -> 记录 sourcePath、contentHash、indexedAt

RagKnowledgeDocumentRepository
  -> 操作 rag_knowledge_document 表
```

用户提问时的 RAG 查询主要由：

```text
EmbeddingStoreContentRetriever
DefaultRetrievalAugmentor
DefaultContentInjector
PgVectorEmbeddingStore
EmbeddingModel
```

这些 LangChain4j 组件完成。

## 5. 哪些是 LangChain4j 自动完成的

今天最重要的边界是：

```text
你负责配置和业务接线。
LangChain4j 负责 AI 执行过程。
```

### 5.1 入库阶段

你写的代码：

```java
ingestor.ingest(enriched);
```

LangChain4j 内部完成：

```text
Document
  -> DocumentSplitter 切片
  -> TextSegment
  -> EmbeddingModel 生成向量
  -> EmbeddingStore 写入 pgvector
```

也就是说：

- 是否需要入库，是你判断的
- 真正切片、向量化、写入，是 LangChain4j 的 `EmbeddingStoreIngestor` 做的

### 5.2 查询阶段

你写的配置：

```java
.contentRetriever(knowledgeContentRetriever)
```

你没有手动写：

```java
contentRetriever.retrieve(...)
```

也没有手动写：

```java
task + 检索片段
```

LangChain4j 在：

```java
assistant.chat(request.userId(), request.task());
```

内部自动完成：

```text
解析 @UserMessage
  -> 拿到用户问题 task
  -> 调用 ContentRetriever
  -> 生成问题向量
  -> 查询 pgvector
  -> 取出相关 TextSegment
  -> 将片段拼进本轮用户消息
  -> 调用 LLM
```

## 6. `@UserMessage` 如何让框架拿到用户问题

问题：

LangChain4j 怎么知道哪个参数是用户问题？

接口定义：

```java
public interface Assistant {
    String chat(@MemoryId String userId, @UserMessage String task);
}
```

调用：

```java
assistant.chat(request.userId(), request.task());
```

LangChain4j 生成的代理对象会在内部解析方法参数注解：

```text
@MemoryId String userId
  -> 这是记忆 ID

@UserMessage String task
  -> 这是用户本轮输入
```

所以它不是靠参数名 `task` 猜的，而是靠：

```java
@UserMessage
```

这个注解决定。

## 7. 为什么 `assistant.chat(...)` 可以直接返回 `String`

问题：

```java
String answer = assistant.chat(request.userId(), request.task());
```

为什么这里可以直接返回一个 `String`？  
明明 `Assistant` 只是一个接口，也没有看到我们自己写 `chat(...)` 的实现。

结论：

**这个 `chat(...)` 方法的真正实现不是项目自己写的，而是 LangChain4j 通过 `AiServices.builder(Assistant.class).build()` 动态生成的代理对象实现的。**

你的接口定义是：

```java
public interface Assistant {
    String chat(@MemoryId String userId, @UserMessage String task);
}
```

这里最关键的是返回值：

```java
String
```

它告诉 LangChain4j：

```text
这个 AI Service 方法最终应该返回普通文本。
```

所以当你调用：

```java
assistant.chat(request.userId(), request.task());
```

内部可以理解成下面这个流程：

```text
调用 Assistant.chat(userId, task)
  -> LangChain4j 代理对象拦截方法调用
  -> 解析方法参数注解
      -> @MemoryId 得到 userId
      -> @UserMessage 得到 task
  -> 构造本轮 UserMessage
  -> 生成 SystemMessage
  -> 处理 ChatMemory
  -> 如果是 RAG，执行 contentRetriever
  -> 如果是 Tool，处理 tool_calls
  -> 调用 ChatModel
  -> 得到模型返回的 AiMessage
  -> 从 AiMessage 中取出文本内容
  -> 根据接口返回类型 String 直接返回文本
```

也就是说，`assistant.chat(...)` 不是一个普通 Java 方法实现，而是一个“被 LangChain4j 接管的方法调用”。

可以把它理解成伪代码：

```java
Object invoke(Method method, Object[] args) {
    // method = Assistant.chat(...)
    // args[0] = userId
    // args[1] = task

    String userId = findArgumentAnnotatedWithMemoryId(method, args);
    String userText = findArgumentAnnotatedWithUserMessage(method, args);

    List<ChatMessage> messages = buildMessages(userId, userText);

    ChatResponse response = chatModel.chat(messages);
    String text = response.aiMessage().text();

    if (method.getReturnType() == String.class) {
        return text;
    }

    // 如果返回值是其他类型，框架会走对应的解析逻辑
}
```

所以这行代码：

```java
String answer = assistant.chat(request.userId(), request.task());
```

能直接拿到字符串，是因为：

1. `Assistant.chat(...)` 的返回类型声明为 `String`。
2. LangChain4j 代理对象负责真正执行调用。
3. 模型最终返回的是一条 AI 文本消息。
4. 框架把 AI 文本消息提取出来，并按方法返回类型返回。

如果接口返回值不是 `String`，情况会不一样。

例如：

```java
Result<String> chat(...);
```

框架可能会返回包含 token、来源内容等更多信息的结果对象。

如果是：

```java
MyDto chat(...);
```

框架就需要尝试把模型输出解析成对应结构。  
这类场景通常需要更明确的输出格式约束，否则容易解析失败。

当前项目使用 `String` 是最简单、最适合学习的方式：

```text
模型回答什么文本，接口就返回什么文本。
```

## 8. 创建 Agent、选择 Agent、执行 Agent 不是一回事

问题：

```java
Assistant assistant = Boolean.FALSE.equals(request.useRag()) ? toolAssistant : ragAssistant;
String answer = assistant.chat(request.userId(), request.task());
```

这里容易误解成：

```text
第一行创建/构造了 agent
第二行又调用 chat
```

但这不是实际流程。

正确理解是：

```text
Config 里的 @Bean 方法
  -> 项目启动时创建 agent

AgentService 里的第一行
  -> 请求进来时选择 agent

assistant.chat(...)
  -> 请求进来时真正执行 agent
```

### 8.1 项目启动时创建 Agent

`KnowledgeBaseConfig` 里的：

```java
@Bean("ragAssistant")
public Assistant ragAssistant(...) {
    return AiServices.builder(Assistant.class)
            ...
            .build();
}
```

以及：

```java
@Bean("toolAssistant")
public Assistant toolAssistant(...) {
    return AiServices.builder(Assistant.class)
            ...
            .build();
}
```

是在 Spring Boot 启动时执行的。

这一步的作用是：

```text
创建 ragAssistant 对象
创建 toolAssistant 对象
放入 Spring 容器
```

它只是配置能力，例如：

```text
使用哪个模型
是否配置 RAG
是否配置 tools
使用哪个 memory provider
使用哪个 systemMessageProvider
使用哪些 guardrail
```

注意：创建 Bean 时不会真正回答用户问题，也不会因为 `.build()` 就调用大模型。

### 8.2 请求进来时选择 Agent

`AgentService` 里的：

```java
Assistant assistant = Boolean.FALSE.equals(request.useRag()) ? toolAssistant : ragAssistant;
```

不是调用构造方法，也不是重新创建 agent。

它只是从已经创建好的两个 Bean 中选一个引用。

等价于：

```java
Assistant assistant;

if (Boolean.FALSE.equals(request.useRag())) {
    assistant = toolAssistant;
} else {
    assistant = ragAssistant;
}
```

含义是：

```text
useRag == false
  -> 使用 toolAssistant

useRag == true 或 useRag 没传
  -> 使用 ragAssistant
```

所以这一行只是“选择用哪个 agent”，不是“执行 agent”。

### 8.3 `assistant.chat(...)` 才是真正执行 Agent

真正触发 LangChain4j 完整流程的是：

```java
String answer = assistant.chat(request.userId(), request.task());
```

这一步会执行：

```text
解析 @MemoryId 和 @UserMessage
生成 system prompt
读取 memory
如果是 ragAssistant，执行 RAG 检索
如果是 toolAssistant，允许模型调用工具
调用 LLM
拿到模型回答
返回 String
```

可以用一句话区分：

```text
@Bean 创建的是“已经配置好的 Agent 对象”。
三元表达式选择的是“本次请求用哪个 Agent”。
chat 调用的是“让这个 Agent 真正处理本次问题”。
```

## 9. `chat` 方法为什么这样定义

项目中的 `Assistant` 接口是：

```java
public interface Assistant {

    String chat(@MemoryId String userId, @UserMessage String task);
}
```

这个 `chat(...)` 方法是你定义的 AI 服务入口。  
但是它的实现不是你自己写的，而是 LangChain4j 根据接口和注解动态生成的。

### 9.1 为什么传入 `userId`

```java
@MemoryId String userId
```

这个参数告诉 LangChain4j：

```text
这是当前对话所属的用户/会话 ID。
```

它的作用是区分不同用户的记忆。

例如：

```text
userId = "1"
  -> 使用用户 1 的历史对话

userId = "2"
  -> 使用用户 2 的历史对话

userId = "-1"
  -> 匿名模式，不保存历史记忆
```

### 9.2 为什么传入 `task`

```java
@UserMessage String task
```

这个参数告诉 LangChain4j：

```text
这是用户本轮真正输入的问题。
```

例如请求体是：

```json
{
  "userId": "1",
  "task": "解释一下 RAG 流程"
}
```

调用时：

```java
assistant.chat("1", "解释一下 RAG 流程");
```

LangChain4j 内部会理解成：

```text
memoryId = "1"
userMessage = "解释一下 RAG 流程"
```

所以这两个参数不是随便传的，而是分别承担不同职责：

```text
userId
  -> 用来找记忆

task
  -> 用来作为用户输入发给模型
```

### 9.3 为什么写成接口格式

这是 LangChain4j `AiServices` 的典型写法。

你写：

```java
AiServices.builder(Assistant.class)
        ...
        .build();
```

意思是：

```text
请 LangChain4j 帮我生成 Assistant 这个接口的实现对象。
```

所以你不需要手动写：

```java
public class AssistantImpl implements Assistant {
    @Override
    public String chat(String userId, String task) {
        ...
    }
}
```

框架会在运行时生成一个代理实现，可以理解成类似：

```java
class LangChain4jGeneratedAssistant implements Assistant {

    @Override
    public String chat(String userId, String task) {
        // 解析 @MemoryId
        // 解析 @UserMessage
        // 组装 system prompt
        // 加载 memory
        // 执行 RAG / Tool
        // 调用 LLM
        // 返回 String
    }
}
```

这个类不在你的源码里，它是框架运行时创建的代理对象。

### 9.4 为什么接口里只写方法声明就够了

因为你是在用声明式方式告诉框架：

```text
这个方法是 AI 服务入口。
第一个参数是记忆 ID。
第二个参数是用户消息。
返回值是模型回答文本。
```

框架根据这些信息完成真正执行流程。

所以：

```java
String chat(@MemoryId String userId, @UserMessage String task);
```

这行代码的含义是：

```text
定义一个 AI 对话入口：
按照 userId 区分记忆，
把 task 当作用户问题，
最终返回一个字符串答案。
```

## 10. 查询和拼接内部如何完成

用户提问时，RAG 查询并不是你直接调用 `retrieve(...)`，而是 LangChain4j 的 RAG 增强流程自动调度。

内部核心组件可以理解成：

```text
DefaultRetrievalAugmentor
  -> 负责整个 RAG 增强调度

EmbeddingStoreContentRetriever
  -> 负责查向量库

DefaultContentInjector
  -> 负责把查到的内容拼进用户消息
```

完整过程：

```text
assistant.chat(userId, task)
  -> 解析 @UserMessage，得到 task
  -> 构造 UserMessage
  -> 进入 DefaultRetrievalAugmentor
  -> 从 UserMessage 中提取文本
  -> 构造 Query
  -> 调用 EmbeddingStoreContentRetriever.retrieve(query)
  -> embeddingModel.embed(query.text())
  -> embeddingStore.search(...)
  -> 得到相似 TextSegment
  -> DefaultContentInjector.inject(...)
  -> 把检索片段拼进 UserMessage
  -> 调用 ChatModel
```

默认拼接模板可以理解为：

```text
{{userMessage}}

Answer using the following information:
{{contents}}
```

所以原始用户问题：

```text
RAG 的向量表怎么设计？
```

会被增强成类似：

```text
RAG 的向量表怎么设计？

Answer using the following information:
检索到的知识片段 1

检索到的知识片段 2
```

注意：RAG 内容通常是拼进 `UserMessage`，不是拼进你的 `SystemMessage`。

## 11. 多个 Agent 的提示词如何分别指定

问题：

模型提示词怎么指定？如果有多个 agent，怎么分别配置？

结论：

每个 assistant 可以在自己的 `AiServices.builder(...)` 里单独指定系统提示词。

RAG agent：

```java
@Bean("ragAssistant")
public Assistant ragAssistant(...) {
    return AiServices.builder(Assistant.class)
            .chatModel(chatModel)
            .contentRetriever(knowledgeContentRetriever)
            .systemMessageProvider(userId -> buildRagSystemMessage((String) userId, conversationMemoryService))
            .build();
}
```

Tool agent：

```java
@Bean("toolAssistant")
public Assistant toolAssistant(...) {
    return AiServices.builder(Assistant.class)
            .chatModel(chatModel)
            .tools(minimalAgentTools)
            .systemMessageProvider(userId -> buildToolSystemMessage((String) userId, conversationMemoryService))
            .build();
}
```

所以多个 agent 的区分方式是：

```text
ragAssistant
  -> buildRagSystemMessage(...)
  -> contentRetriever(...)

toolAssistant
  -> buildToolSystemMessage(...)
  -> tools(...)
```

如果后续要新增 agent，也可以继续定义新的 Bean，例如：

```java
@Bean("codeAssistant")
public Assistant codeAssistant(...) {
    return AiServices.builder(Assistant.class)
            .chatModel(chatModel)
            .systemMessageProvider(userId -> buildCodeSystemMessage((String) userId, conversationMemoryService))
            .build();
}
```

## 12. 用户信息最终如何拼给模型

最终给模型的消息不是一个简单字符串，而是多个消息组成的上下文。

RAG 模式下可以理解为：

```text
SystemMessage:
  系统规则
  + 当前 agent 的身份
  + 历史记忆摘要

UserMessage:
  用户原始问题
  + RAG 检索到的知识片段

ChatMemory:
  最近历史消息
```

其中：

- 系统提示词来自 `systemMessageProvider(...)`
- 用户问题来自 `@UserMessage`
- 用户 ID 来自 `@MemoryId`
- 历史摘要来自 `ConversationMemoryService.buildMemoryContext(userId)`
- RAG 片段来自 `ContentRetriever`

Tool 模式下可以理解为：

```text
SystemMessage:
  工具调用规则
  + 历史记忆摘要

UserMessage:
  用户原始问题

Tools:
  webSearch
  visitWebpage
```

## 13. `ragAssistant` 和 `toolAssistant` 逐行解释

这一段代码是项目中两个不同 Agent 的核心配置。  
它们返回的类型都是 `Assistant`，但是能力不同：

```text
ragAssistant
  -> 知识库问答
  -> 挂载 ContentRetriever

toolAssistant
  -> 工具调用
  -> 挂载 MinimalAgentTools
```

### 13.1 `ragAssistant` 逐行解释

代码：

```java
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
```

逐行说明：

```java
@Bean("ragAssistant")
```

把这个方法返回的对象交给 Spring 容器管理，并命名为 `ragAssistant`。  
后面在 `AgentService` 中通过：

```java
@Qualifier("ragAssistant")
```

就能明确注入这个知识库问答 Agent。

```java
public Assistant ragAssistant(...)
```

说明这个 Bean 的类型是 `Assistant`。  
注意：你没有手写 `Assistant` 的实现类，真正的实现对象由 LangChain4j 通过 `AiServices.builder(...).build()` 动态生成。

```java
OpenAiChatModel chatModel
```

注入聊天模型 Bean。  
它负责最终调用大模型生成答案，比如当前项目中的 `qwen-plus`。

```java
ContentRetriever knowledgeContentRetriever
```

注入 RAG 检索器。  
它负责根据用户问题去向量库 `knowledge_embeddings` 中查询相似知识片段。

```java
ChatMemoryProvider chatMemoryProvider
```

注入对话记忆提供器。  
它根据 `@MemoryId` 对应的 `userId` 获取该用户的对话记忆。

```java
PromptInjectionInputGuardrail promptInjectionInputGuardrail
```

注入输入护栏。  
在请求进入模型之前，先检查用户输入是否为空、过长、或者疑似提示词注入。

```java
ResponseSanityOutputGuardrail responseSanityOutputGuardrail
```

注入输出护栏。  
在模型生成答案之后，检查回答是否符合基本要求。

```java
OutputGuardrailsConfig outputGuardrailsConfig
```

注入输出护栏配置。  
当前项目里主要配置了输出护栏失败后的重试次数。

```java
ConversationMemoryService conversationMemoryService
```

注入你自己写的业务记忆服务。  
它负责从数据库中读取历史对话，并构造成历史摘要和最近原文。

```java
return AiServices.builder(Assistant.class)
```

使用 LangChain4j 创建 `Assistant` 接口的代理对象。  
`Assistant.class` 告诉框架：我要生成这个接口的实现。

```java
.chatModel(chatModel)
```

指定这个 Agent 使用哪个聊天模型。  
最终调用 LLM 的能力来自这里。

```java
.chatMemoryProvider(chatMemoryProvider)
```

指定对话记忆提供器。  
当调用：

```java
assistant.chat(userId, task)
```

时，LangChain4j 会根据 `@MemoryId` 拿到 `userId`，再通过这个 provider 找到对应记忆。

```java
.contentRetriever(knowledgeContentRetriever)
```

这是 RAG 模式最关键的一行。  
它告诉 LangChain4j：这个 Agent 回答前要先做知识库检索。

内部会自动完成：

```text
用户问题
  -> 生成问题向量
  -> 查询 pgvector
  -> 取出相似 TextSegment
  -> 拼进 UserMessage
```

```java
.inputGuardrails(promptInjectionInputGuardrail)
```

注册输入护栏。  
用户输入会先经过这个护栏，检查通过后才继续进入后续流程。

```java
.outputGuardrails(responseSanityOutputGuardrail)
```

注册输出护栏。  
模型生成答案后，会经过这个护栏检查。

```java
.outputGuardrailsConfig(outputGuardrailsConfig)
```

指定输出护栏的配置。  
例如输出护栏失败时最多重试几次。

```java
.systemMessageProvider(userId -> buildRagSystemMessage((String) userId, conversationMemoryService))
```

指定系统提示词如何生成。  
这里传入的是一个 lambda，LangChain4j 会把当前 `userId` 传进来，然后调用：

```java
buildRagSystemMessage(...)
```

这个方法会生成 RAG 模式专用系统提示词，并把历史记忆摘要拼进去。

```java
.build();
```

真正创建 `Assistant` 代理对象。  
创建完成后，调用 `assistant.chat(...)` 时，就会按上面这些配置执行。

### 13.2 `toolAssistant` 逐行解释

代码：

```java
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
```

逐行说明：

```java
@Bean("toolAssistant")
```

把这个方法返回的对象交给 Spring 容器管理，并命名为 `toolAssistant`。  
后面可以通过：

```java
@Qualifier("toolAssistant")
```

明确注入工具调用 Agent。

```java
public Assistant toolAssistant(...)
```

说明这个 Bean 的返回类型也是 `Assistant`。  
虽然它和 `ragAssistant` 类型一样，但由于 Bean 名不同，所以 Spring 可以区分它们。

```java
OpenAiChatModel chatModel
```

注入聊天模型。  
Tool 模式最终也需要 LLM 判断是否调用工具，并根据工具结果生成最终答案。

```java
MinimalAgentTools minimalAgentTools
```

注入你自己定义的工具类。  
里面的方法通过 `@Tool` 暴露给 LangChain4j，例如：

```java
webSearch(...)
visitWebpage(...)
```

```java
ChatMemoryProvider chatMemoryProvider
```

注入对话记忆提供器。  
Tool 模式同样可以按 `userId` 使用用户历史记忆。

```java
PromptInjectionInputGuardrail promptInjectionInputGuardrail
```

注入输入护栏。  
避免用户输入中出现明显的越权指令或提示词注入。

```java
ResponseSanityOutputGuardrail responseSanityOutputGuardrail
```

注入输出护栏。  
检查模型最终输出。

```java
OutputGuardrailsConfig outputGuardrailsConfig
```

注入输出护栏配置。

```java
ConversationMemoryService conversationMemoryService
```

注入业务记忆服务。  
用于生成工具模式下的系统提示词时拼接历史摘要。

```java
return AiServices.builder(Assistant.class)
```

创建 `Assistant` 接口的 LangChain4j 代理。

```java
.chatModel(chatModel)
```

指定使用哪个 LLM。

```java
.chatMemoryProvider(chatMemoryProvider)
```

指定对话记忆提供器。

```java
.tools(minimalAgentTools)
```

这是 Tool 模式最关键的一行。  
它把 `MinimalAgentTools` 中带有 `@Tool` 注解的方法注册给 LangChain4j。

内部流程是：

```text
模型判断需要工具
  -> 返回 tool_calls
  -> LangChain4j 解析 tool_calls
  -> 反射调用 MinimalAgentTools 中的 @Tool 方法
  -> 工具结果回填给模型
  -> 模型生成最终答案
```

```java
.inputGuardrails(promptInjectionInputGuardrail)
```

注册输入护栏。

```java
.outputGuardrails(responseSanityOutputGuardrail)
```

注册输出护栏。

```java
.outputGuardrailsConfig(outputGuardrailsConfig)
```

指定输出护栏配置。

```java
.systemMessageProvider(userId -> buildToolSystemMessage((String) userId, conversationMemoryService))
```

指定工具模式的系统提示词生成方式。  
这里会调用：

```java
buildToolSystemMessage(...)
```

生成 Tool agent 专用提示词，例如要求模型遇到事实查询、网页查询时优先使用工具。

```java
.build();
```

构建最终的 `toolAssistant` 代理对象。

### 13.3 两个 Agent 的核心区别

两者大部分配置相同：

```text
chatModel
chatMemoryProvider
inputGuardrails
outputGuardrails
outputGuardrailsConfig
systemMessageProvider
```

真正决定能力差异的是：

```java
ragAssistant:
    .contentRetriever(knowledgeContentRetriever)

toolAssistant:
    .tools(minimalAgentTools)
```

因此：

```text
ragAssistant
  -> 适合查询本地知识库
  -> 回答前自动做向量检索

toolAssistant
  -> 适合联网查询、网页读取等外部动作
  -> 模型需要时自动触发 @Tool 方法
```

## 14. `EmbeddingStoreContentRetriever.retrieve(query)` 是谁写的

问题：

```java
EmbeddingStoreContentRetriever.retrieve(query)
```

这个方法是项目自己写的吗？

结论：

不是。它是 LangChain4j 框架内部提供的方法。

你写的是配置：

```java
EmbeddingStoreContentRetriever.builder()
        .embeddingStore(embeddingStore)
        .embeddingModel(embeddingModel)
        .maxResults(4)
        .minScore(0.55)
        .build();
```

LangChain4j 内部实现 `retrieve(query)`，大致做：

```text
query.text()
  -> embeddingModel.embed(query.text())
  -> 构造 EmbeddingSearchRequest
  -> embeddingStore.search(request)
  -> 将命中结果转为 Content 列表
```

所以：

- `retrieve(...)` 不是你写的
- 你也没有手动调用它
- 它是在 `assistant.chat(...)` 内部由 LangChain4j 自动触发的

## 15. 你负责的代码和框架负责的代码

### 15.1 你负责的部分

```text
AgentController
  -> 定义 HTTP 入口

AgentService
  -> 根据 useRag 选择 ragAssistant 或 toolAssistant

Assistant
  -> 定义 chat 方法，并用 @MemoryId、@UserMessage 标注参数

KnowledgeBaseConfig
  -> 配置 ChatModel
  -> 配置 EmbeddingModel
  -> 配置 PgVectorEmbeddingStore
  -> 配置 ContentRetriever
  -> 配置 ragAssistant / toolAssistant
  -> 配置系统提示词

RagKnowledgeIndexService
  -> 读取 knowledge 目录
  -> 计算文件 hash
  -> 判断是否需要重新入库
  -> 删除旧向量
  -> 调用 ingestor.ingest(enriched)

RagKnowledgeDocument / Repository
  -> 维护文件级索引记录

ConversationMemoryService
  -> 构建历史摘要
  -> 保存本轮问答
```

### 15.2 LangChain4j 负责的部分

```text
AiServices
  -> 生成 Assistant 代理

@MemoryId / @UserMessage 解析
  -> 找到记忆 ID 和用户输入

EmbeddingStoreIngestor
  -> 文档切片
  -> 片段向量化
  -> 写入向量库

EmbeddingStoreContentRetriever
  -> 用户问题向量化
  -> 向量库相似度搜索

DefaultRetrievalAugmentor
  -> 调度查询增强流程

DefaultContentInjector
  -> 把检索内容拼进用户消息

OpenAiChatModel
  -> 调用大模型接口

Tool 调用机制
  -> 解析 tool_calls
  -> 反射执行 @Tool 方法
```

## 16. 今日最终理解

今天这条线可以总结成一句话：

**项目自己负责配置模型、配置向量库、同步知识文件、选择 agent、管理记忆；LangChain4j 负责在 `assistant.chat(...)` 内部解析用户输入、执行 RAG 检索、拼接检索结果、调用大模型并返回答案。**

再压缩一下：

```text
你写业务控制和配置。
LangChain4j 执行 AI 工作流。
pgvector 存储和检索向量。
LLM 根据最终上下文生成答案。
```
