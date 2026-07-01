# minimal-agent-springboot

这是把原始 Python 项目 [`minimal-agent`](../original) 改造成的 **Java Spring Boot 版 Agent 服务**。

原项目保留在 `../original` 目录中，作为参考实现；当前目录是可直接启动运行的 Spring Boot 版本。

## 这个项目是什么

它是一个很轻量的 Agent，核心流程很简单：

1. 接收一句自然语言任务
2. 交给大模型分析
3. 大模型在需要时调用工具
4. 工具返回结果后继续推理
5. 最后输出答案

这个版本保留了原项目的核心思路，但把它改成了更适合 Java 学习和 Spring Boot 集成的形式。

## 启动后有什么功能

启动后，这个项目会提供一个 HTTP 服务，你可以通过接口让它执行任务。

当前可用能力：

- 网页搜索
- 访问网页并提取正文
- 由大模型根据任务自动决定是否调用工具
- 返回最终答案

它**没有前端页面**，默认就是一个后端 API 服务。

你可以把它理解成：

- 不是聊天网页
- 不是桌面程序
- 而是一个可调用的 Agent 后端

## 当前实现的核心功能

### 1. 大模型对话

通过 LangChain4j 的 `@AiService` 把大模型包装成一个 Spring Bean。

### 2. 工具调用

通过 `@Tool` 暴露两个工具给模型：

- `webSearch(String query)`：网页搜索
- `visitWebpage(String url)`：访问网页并提取文本

### 3. 任务执行接口

通过 `POST /api/agent/run` 提交任务，服务会返回 Agent 的最终答案。

## 技术栈

- Java 17
- Spring Boot 3.5
- LangChain4j
- Jsoup

## 目录结构

```text
springboot-refactor/
├── pom.xml
├── README.md
└── src/main
    ├── java/com/antropath/minimalagent
    │   ├── MinimalAgentSpringbootApplication.java
    │   ├── api
    │   │   ├── AgentController.java
    │   │   ├── AgentRequest.java
    │   │   └── AgentResponse.java
    │   └── agent
    │       ├── Assistant.java
    │       ├── AgentService.java
    │       └── MinimalAgentTools.java
    └── resources
        └── application.yml
```

## 启动前准备

你已经把模型配置写死在 `application.yml` 里了，所以本地直接点 Spring Boot 启动即可。

如果你想改模型，只需要改这里：

`src/main/resources/application.yml`

当前配置是：

- `base-url`: `https://dashscope.aliyuncs.com/compatible-mode/v1`
- `model-name`: `qwen3.7-plus`
- `api-key`: 已写入配置文件

## 启动方式

### 方式一：IDEA 直接启动

打开 `MinimalAgentSpringbootApplication`，点击运行即可。

### 方式二：命令行启动

在项目根目录执行：

```bash
mvn spring-boot:run
```

### 方式三：打包启动

```bash
mvn clean package
java -jar target/minimal-agent-springboot-0.0.1-SNAPSHOT.jar
```

## 启动后怎么用

服务启动成功后，默认监听：

```text
http://localhost:8080
```

调用接口：

```text
POST /api/agent/run
```

请求体示例：

```json
{
  "task": "What was the hottest day in 2024 and how much was the Dow Jones on that day?"
}
```

返回体示例：

```json
{
  "answer": "..."
}
```

## curl 示例

```bash
curl -X POST http://localhost:8080/api/agent/run ^
  -H "Content-Type: application/json" ^
  -d "{\"task\":\"What was the hottest day in 2024 and how much was the Dow Jones on that day?\"}"
```

## 代码说明

### `MinimalAgentSpringbootApplication.java`

Spring Boot 启动入口。

### `AgentController.java`

对外暴露 HTTP 接口，接收用户任务并返回答案。

### `AgentService.java`

业务层封装，负责调用 AI Service。

### `Assistant.java`

LangChain4j 的 AI Service 接口，负责让模型理解任务并自动调用工具。

### `MinimalAgentTools.java`

模型可调用的工具类：

- `webSearch`
- `visitWebpage`

## 这个项目的运行逻辑

大致是这样的：

1. 你调用 `/api/agent/run`
2. `AgentController` 把任务交给 `AgentService`
3. `AgentService` 调用 `Assistant`
4. `Assistant` 让大模型判断是否需要工具
5. 如果需要，模型调用 `webSearch` 或 `visitWebpage`
6. 工具返回结果后，模型继续推理
7. 最终输出答案

## 适合做什么

这个项目适合：

- 学习 Spring Boot + 大模型接入
- 学习 LangChain4j 的 AI Service 写法
- 学习最小化 Agent 的工作方式
- 作为后续扩展的基础工程

## 目前的限制

- 只有后端接口，没有前端页面
- 搜索依赖外网
- 网页抓取可能会遇到反爬限制
- 没有做复杂的多轮状态编排
- 没有接入 langgraph4j

## 和原 Python 项目的关系

原版 Python 项目是一个极简 ReAct agent。

这个 Java 版保留了同样的思想，只是换成了 Spring Boot 的组织方式：

- Python 脚本入口 -> Spring Boot REST 服务
- Python 工具类 -> Spring Bean 工具类
- Python 模型调用 -> LangChain4j AI Service

## 可以继续扩展的方向

- 增加更多工具，比如文件读取、数据库查询
- 增加任务执行日志接口
- 增加前端页面
- 增加多 Agent 协作
- 后续需要复杂流程时接入 `langgraph4j`

## 原始项目

Python 原版仓库位于：

```text
../original
```


## 工具类的实现流程

这一部分专门讲 `MinimalAgentTools` 是怎么工作的。它是这个项目里最关键的“外部能力入口”。

### 1. Spring 先把工具类注册成 Bean

```java
@Component
public class MinimalAgentTools {
```

`@Component` 的作用是让 Spring 在启动时把它创建出来，放进容器里。这样 LangChain4j 后面才能拿到这个对象，并把里面的工具方法暴露给大模型。

如果没有 `@Component`，这个类虽然写在项目里，但 Spring 不会自动管理它，LangChain4j 也就没有现成对象可调用。

### 2. 构造方法先读取配置

```java
public MinimalAgentTools(
        @Value("${agent.search.max-results:5}") int maxSearchResults,
        @Value("${agent.webpage.max-characters:8000}") int maxWebpageCharacters
)
```

这里的作用是把外部配置注入到工具类里。

- `maxSearchResults` 控制搜索结果最多返回几条
- `maxWebpageCharacters` 控制网页正文最多截断多少字符

这两个值不是模型决定的，而是你在配置文件里提前定好的。

### 3. `webSearch` 的执行流程

`webSearch(String query)` 的职责是把“搜索关键词”变成“可读的搜索结果”。

流程是：

1. 接收模型传来的 `query`
2. 拼出 DuckDuckGo 的搜索地址
3. 用 Jsoup 发起网页请求
4. 解析 HTML 页面中的搜索结果块
5. 提取标题、链接和摘要
6. 按 `maxSearchResults` 截取结果数量
7. 把结果整理成文本返回给模型

核心代码是：

```java
String url = "https://duckduckgo.com/html/?q=" + encode(query);
Document document = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(20_000).get();
Elements results = document.select(".result");
```

然后逐条提取：

```java
Element title = result.selectFirst(".result__title a");
Element snippet = result.selectFirst(".result__snippet");
```

最后返回给模型的不是 HTML，而是更容易读的纯文本或 Markdown 风格文本。

### 4. `visitWebpage` 的执行流程

`visitWebpage(String url)` 的职责是“直接打开网页内容并提取正文”。

流程是：

1. 接收模型传来的 URL
2. 用 `HttpClient` 访问网页
3. 用 Jsoup 解析页面源码
4. 删除脚本、样式、无用节点
5. 抽取正文文本
6. 做空白字符清理
7. 超过最大长度就截断
8. 返回给模型

核心代码是：

```java
HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .header("User-Agent", "Mozilla/5.0")
        .GET()
        .build();
HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
Document document = Jsoup.parse(response.body(), url);
```

然后清理无关内容：

```java
document.select("script,style,noscript,svg").remove();
```

再提取正文：

```java
String text = document.text().replaceAll("\\s+", " ").trim();
```

### 5. 为什么工具方法能被模型自动调用

因为方法上有 `@Tool`：

```java
@Tool("Search the web and return a short list of relevant results with titles, URLs, and snippets.")
public String webSearch(String query)
```

LangChain4j 会把它识别成一个可调用工具，并生成工具元数据：

- 名字：`webSearch`
- 描述：注解里的说明文字
- 参数：`query`
- 返回值：`String`

模型看到的就是这份工具说明，不是 Java 源码本身。

### 6. 这个工具类在 Agent 里的角色

在整个调用链中，`MinimalAgentTools` 相当于 Agent 的“手脚”。

- 模型负责思考
- `MinimalAgentTools` 负责真的去搜索、真的去读网页
- LangChain4j 负责把模型的意图转换成 Java 方法调用

所以当你问一个需要联网的问题时，最后真正去执行外部访问的，就是这个工具类。

### 7. 为什么现在它适合做最小 Agent

因为它很简单，只有两个动作：

- 查
- 看

但这已经足够把“会说话的模型”变成“能执行任务的 Agent”了。后续如果你要做更像产品的版本，可以继续往这里加工具，比如：

- `currentDateTime()`：查当前时间
- `readLocalFile()`：读本地文件
- `queryDatabase()`：查数据库
- `callHttpApi()`：调内部接口

一旦工具变多，模型就不只是回答问题，而是可以真的帮你做事。
