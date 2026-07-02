# 项目里的 RAG 流程详解

这份笔记结合当前代码，专门解释这个 Spring Boot 项目里的 RAG 是怎么跑起来的，以及为什么有些问题会被回答成“没有相关信息”。

## 1. 这个项目里，RAG 的入口在哪里

你现在的 HTTP 入口是：

- [AgentController.java](C:\Users\86187\Desktop\老桌面\学习笔记\Java学习\大三暑假\agent_demo\springboot-refactor\src\main\java\com\antropath\minimalagent\api\AgentController.java:10)

核心方法是：

```java
@PostMapping("/run")
public AgentResponse run(@Valid @RequestBody AgentRequest request) {
    return new AgentResponse(agentService.answer(request.task()));
}
```

这里的作用很简单：

1. 接收前端或接口调用传来的 `task`
2. 把 `task` 交给 `AgentService`
3. 返回一个 `AgentResponse`

也就是说，RAG 不直接暴露在 Controller 里，而是藏在下面一层的服务和配置里。

---

## 2. 请求是怎么一路走到模型的

调用链可以直接按代码看：

### 2.1 `AgentController`

- [AgentController.java](C:\Users\86187\Desktop\老桌面\学习笔记\Java学习\大三暑假\agent_demo\springboot-refactor\src\main\java\com\antropath\minimalagent\api\AgentController.java:10)

```java
return new AgentResponse(agentService.answer(request.task()));
```

### 2.2 `AgentService`

- [AgentService.java](C:\Users\86187\Desktop\老桌面\学习笔记\Java学习\大三暑假\agent_demo\springboot-refactor\src\main\java\com\antropath\minimalagent\agent\AgentService.java:5)

```java
public String answer(String task) {
    return assistant.chat(task);
}
```

这里 `AgentService` 只是一个很薄的业务层：

- 不做检索
- 不做分片
- 不做向量化
- 不关心知识库怎么建

它只是把任务交给 `Assistant`。

### 2.3 `Assistant`

- [Assistant.java](C:\Users\86187\Desktop\老桌面\学习笔记\Java学习\大三暑假\agent_demo\springboot-refactor\src\main\java\com\antropath\minimalagent\agent\Assistant.java:1)

现在这个接口已经被简化成：

```java
public interface Assistant {
    String chat(String task);
}
```

它本身没有实现逻辑，真正的实现是在配置类里通过 `AiServices.builder(...)` 生成的。

---

## 3. RAG 的核心配置在哪里

真正把 RAG 串起来的是这个类：

- [KnowledgeBaseConfig.java](C:\Users\86187\Desktop\老桌面\学习笔记\Java学习\大三暑假\agent_demo\springboot-refactor\src\main\java\com\antropath\minimalagent\agent\KnowledgeBaseConfig.java:23)

这个类里有三个最关键的 Bean：

1. `embeddingModel`
2. `embeddingStore`
3. `knowledgeContentRetriever`
4. `assistant`

下面按顺序讲。

---

## 4. 文档是怎么变成向量的

### 4.1 先从本地目录读取知识文件

代码在这里：

```java
Path path = Path.of(knowledgePath);
if (Files.exists(path)) {
    List<Document> documents = FileSystemDocumentLoader.loadDocumentsRecursively(path);
```

这说明你的知识来源不是数据库，而是项目里的本地目录：

- 默认目录：`knowledge/`

对应配置在：

- [application.yml](C:\Users\86187\Desktop\老桌面\学习笔记\Java学习\大三暑假\agent_demo\springboot-refactor\src\main\resources\application.yml:27)

```yaml
rag:
  knowledge-path: knowledge
```

也就是说，只要你把 `.md`、`.txt` 这类文件放进 `knowledge/`，启动时就会被读进去。

---

### 4.2 再把长文档切成小块

代码在这里：

```java
EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
        .documentSplitter(DocumentSplitters.recursive(700, 100))
        .embeddingModel(embeddingModel)
        .embeddingStore(store)
        .build();
```

这一步就是分片。

含义是：

- 每片尽量控制在 700 左右
- 片与片之间保留 100 的重叠

为什么要切？

- 文档太长，检索精度会下降
- 小片段更容易命中具体问题
- 也更省 token

这一步是**本地算法**完成的，不需要模型，不需要秘钥。

---

### 4.3 文本怎么变成向量

代码在这里：

```java
return OpenAiEmbeddingModel.builder()
        .baseUrl(embeddingBaseUrl)
        .apiKey(embeddingApiKey)
        .modelName(embeddingModelName)
        .logRequests(true)
        .logResponses(true)
        .build();
```

这就是 embedding 模型。

它负责把：

- 文档片段
- 用户问题

都转成向量。

注意一点很重要：

**入库时用什么 embedding 模型，查询时也要用同一个模型。**

不然向量坐标系不一致，检索就会乱。

---

## 5. 向量存到哪里

代码在这里：

```java
InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
```

所以当前版本的数据存储方式是：

- 原始文档：还在磁盘上的 `knowledge/`
- 切完后得到的向量和片段：存在 JVM 内存里

这意味着：

- 服务重启后，向量会消失
- 启动时会重新扫描 `knowledge/`
- 适合学习、demo、小项目

如果以后想持久化，可以把它换成：

- Milvus
- Qdrant
- Redis Vector
- PGVector
- Elasticsearch 向量检索

---

## 6. 检索器是怎么找答案的

代码在这里：

```java
return EmbeddingStoreContentRetriever.builder()
        .embeddingStore(embeddingStore)
        .embeddingModel(embeddingModel)
        .maxResults(4)
        .minScore(0.55)
        .build();
```

它的作用是：

1. 把用户问题也转成向量
2. 去 `embeddingStore` 里比对相似度
3. 取最相关的前 4 条
4. 过滤掉分数低于 `0.55` 的结果

所以它不是“回答问题”的组件，而是“找资料”的组件。

---

## 7. 最后是谁负责回答

代码在这里：

```java
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
```

这里做了两件事：

### 7.1 创建聊天模型

它负责最后生成自然语言回答。

### 7.2 把聊天模型和检索器装起来

`AiServices.builder(...)` 会把下面几件事自动串起来：

- 收到用户问题
- 先调用 `contentRetriever` 找相关片段
- 再把片段连同问题交给 `chatModel`
- 最后输出答案

所以你调用的虽然是：

```java
assistant.chat(task)
```

但底层其实已经做了 RAG。

---

## 8. 为什么有些问题会被回答成“没有相关信息”

因为当前提示词和检索策略都偏保守。

现在这个项目的目标不是“什么都能答”，而是：

- 优先根据知识库回答
- 知识库里没有，就明确说明没有相关资料

所以如果你问的内容不在 `knowledge/` 里，模型就会返回类似：

> 根据您提供的资料，其中没有关于该主题的信息。

这不是报错，而是这套 RAG 的正常表现。

---

## 9. 配置文件里有哪些关键项

看这里：

- [application.yml](C:\Users\86187\Desktop\老桌面\学习笔记\Java学习\大三暑假\agent_demo\springboot-refactor\src\main\resources\application.yml:8)

```yaml
langchain4j:
  open-ai:
    chat-model:
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      api-key: ${OPENAI_API_KEY:}
      model-name: qwen-plus
    embedding-model:
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      api-key: ${OPENAI_API_KEY:}
      model-name: text-embedding-v4
```

这里说明两件事：

1. chat 和 embedding 目前共用同一个环境变量 `OPENAI_API_KEY`
2. 你用的是兼容 OpenAI 协议的云端服务

---

## 10. 这个项目现在的完整链路

把整个流程串起来就是：

```text
启动 Spring Boot
  ↓
读取 knowledge/ 下的文档
  ↓
本地切分文档
  ↓
embedding 模型把文本变向量
  ↓
向量和片段存入 InMemoryEmbeddingStore
  ↓
用户调用 POST /api/agent/run
  ↓
AgentController 接收请求
  ↓
AgentService 调用 assistant.chat()
  ↓
retriever 找最相关片段
  ↓
chat model 基于片段生成回答
  ↓
返回 AgentResponse
```

---

## 11. 这版项目适合做什么

适合：

- 学 RAG 基本流程
- 学 LangChain4j 的 Java 接法
- 学 Spring Boot + AI 服务的接线方式
- 做小型知识库问答 demo

不太适合直接当生产版，因为：

- 向量库是内存型
- 重启会丢数据
- 没有复杂的权限、版本和来源管理

---

## 12. 后面你可以怎么扩展

如果后面要继续做，可以往这几个方向走：

- 换持久化向量库
- 增加来源引用
- 让知识库答不上来时走通用问答兜底
- 支持数据库、网页、PDF 导入
- 给不同知识源加 metadata

---

## 13. 你该优先看的代码

按优先级，我建议你先看：

1. [KnowledgeBaseConfig.java](C:\Users\86187\Desktop\老桌面\学习笔记\Java学习\大三暑假\agent_demo\springboot-refactor\src\main\java\com\antropath\minimalagent\agent\KnowledgeBaseConfig.java:23)
2. [AgentService.java](C:\Users\86187\Desktop\老桌面\学习笔记\Java学习\大三暑假\agent_demo\springboot-refactor\src\main\java\com\antropath\minimalagent\agent\AgentService.java:5)
3. [AgentController.java](C:\Users\86187\Desktop\老桌面\学习笔记\Java学习\大三暑假\agent_demo\springboot-refactor\src\main\java\com\antropath\minimalagent\api\AgentController.java:10)
4. [application.yml](C:\Users\86187\Desktop\老桌面\学习笔记\Java学习\大三暑假\agent_demo\springboot-refactor\src\main\resources\application.yml:8)

