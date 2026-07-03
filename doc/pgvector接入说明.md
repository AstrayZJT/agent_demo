# pgvector 接入说明

这份说明记录本项目把 RAG 从内存向量库切到 PostgreSQL + pgvector 的过程。

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

- PostgreSQL 18
- pgvector Docker 镜像：`pgvector/pgvector:pg18`
- 端口：`5433`
- 数据库名：`agentdemo_rag`

当前容器已经启动，并且 `vector` 扩展已经启用。

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

这样启动时会把 `knowledge/` 里的内容切片后写进 pgvector 表里。

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

如果你要自己重启这个 RAG 库，可以用下面的容器命令：

```bash
docker run -d --name agentdemo-pgvector -p 5433:5432 \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=你的密码 \
  -e POSTGRES_DB=agentdemo_rag \
  pgvector/pgvector:pg18
```

启动后再执行：

```bash
docker exec -e PGPASSWORD=你的密码 agentdemo-pgvector \
  psql -U postgres -d agentdemo_rag -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

---

## 6. 你现在该关注的配置

默认情况下，这些值已经写进项目：

- `localhost:5433`
- `agentdemo_rag`
- `postgres`

如果你后面想换成自己的数据库，只要改环境变量即可。

---

## 7. 怎么判断接入成功

你可以看这几个信号：

- 容器能启动
- `vector` 扩展能查到
- 项目启动时不会在 RAG 初始化阶段报数据库错误
- 发起问题后能检索出知识库内容

如果检索不到，通常是：

- 数据没写进去
- 分数阈值太高
- embedding 模型和查询模型不匹配
- pgvector 容器没起来

