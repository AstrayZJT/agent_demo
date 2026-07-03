# pgvector 接入说明

这份说明记录本项目把 RAG 从内存向量库切到本机 PostgreSQL + pgvector 的过程。

---

## 1. 为什么要接 pgvector

之前的 RAG 是：

- 读取 `knowledge/`
- 切片
- 生成向量
- 存到内存里

这样的问题是：

- 程序重启后数据会丢
- 不方便做持久化知识库
- 以后资料一多，内存方案不够稳

所以这次改成了 pgvector。

---

## 2. 本次用到的环境

- 本机 PostgreSQL 18
- `vector` 扩展已安装到本机 PostgreSQL
- 端口：`5432`
- 数据库名：`agentdemo`

当前本机数据库已经启用 `vector` 扩展。

---

## 3. 项目里改了什么

### 3.1 依赖

新增了：

- `langchain4j-pgvector`

### 3.2 配置

新增了这些 RAG 配置项：

- `rag.pgvector.host`
- `rag.pgvector.port`
- `rag.pgvector.database`
- `rag.pgvector.username`
- `rag.pgvector.password`
- `rag.pgvector.table`

### 3.3 代码

`KnowledgeBaseConfig` 里的 embedding store 从内存实现改成了：

- `PgVectorEmbeddingStore`

现在启动时会把 `knowledge/` 里的内容做增量同步后写进 pgvector 表里。

---

## 4. 现在的流程

```text
knowledge/ 文档
-> 文档切片
-> 生成 embedding
-> 写入 pgvector
-> 用户提问
-> 向量检索
-> 取出最相近片段
-> 交给 LLM
```

---

## 5. 本机怎么启动

你不需要再单独起 Docker 向量库了。  
只要本机 PostgreSQL 服务在跑，并且数据库里已经创建了 `vector` 扩展，就可以直接用。

如果还没创建扩展，可以执行：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

---

## 6. 你现在该关注的配置

默认情况下，这些值已经写进项目：

- `localhost:5432`
- `agentdemo`
- `postgres`

如果你后面想换成自己的数据库，只要改环境变量即可。

---

## 7. 怎么判断接入成功

你可以看这几个信号：

- 本机 PostgreSQL 服务能启动
- `vector` 扩展能查到
- 项目启动时不会在 RAG 同步阶段报数据库错误
- 发起问题后能检索出知识库内容

如果检索不到，通常是：

- 数据没写进去
- 分数阈值太高
- embedding 模型和查询模型不匹配
- PostgreSQL 服务没起来
