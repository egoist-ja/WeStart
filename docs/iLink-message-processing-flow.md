# WeStart iLink 微信智能助手消息处理全流程

## 概述

WeStart 是一个基于 iLink SDK 的微信 AI 聊天助手，以 Spring Boot 应用运行，通过 LangChain4j 框架对接 Qwen 多模态大模型。本文档描述从用户发送微信消息到 AI 生成回复并投递的完整链路。

---

## 架构总览

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              微信用户 (WeChat)                                    │
└────────────────────────────────┬────────────────────────────────────────────────┘
                                 │ 文本 / 图片 / 语音 / 文件
                                 ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         iLink SDK (轮询接收消息)                                   │
│   ILinkClientFactory: 每个登录会话一个独立 ILinkClient 实例                          │
│   - onMessage 回调 → UserThreadService.handleMessages()                          │
│   - onHeartbeat 回调 → 鉴权失效检测与登录状态维护                                    │
└────────────────────────────────┬────────────────────────────────────────────────┘
                                 │ ConcurrentLinkedQueue<WeixinMessage>
                                 ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                    用户虚拟线程 (Thread.ofVirtual())                                │
│   UserThreadServiceImpl: 每个会话一条虚拟线程，串行处理                                │
│   - 从队列批量收集消息（最多 20 条/批次）                                             │
│   - 开启/关闭微信「正在输入」状态                                                    │
└────────────────────────────────┬────────────────────────────────────────────────┘
                                 │ List<WeixinMessage>
                                 ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                     消息解析与模型调用 (UserMessageServiceImpl)                      │
│   - 消息类型解析：文本 | 图片(下载+Base64) | 语音(转写) | 文件(提取)                    │
│   - 发布至 Redis Stream 记录聊天历史                                                 │
│   - 加载长期记忆上下文 (MySQL + 记忆模型)                                            │
│   - 组装多模态 List<Content> → 调用 WeChatAssistant                                │
└────────────────────────────────┬────────────────────────────────────────────────┘
                                 │
                    ┌────────────┼────────────┐
                    ▼            ▼            ▼
┌──────────────────────┐ ┌────────────┐ ┌──────────────────────┐
│  Redis 短期记忆        │ │ 大模型      │ │  长期记忆处理 (异步)    │
│  TokenWindowChatMemory│ │ Qwen 多模态 │ │  Redis Stream → MySQL │
│  (Qwen Tokenizer)     │ │            │ │  MemoryAssistant     │
└──────────────────────┘ └─────┬──────┘ └──────────────────────┘
                               │
                    ┌──────────┼──────────┐
                    ▼                     ▼
          ┌──────────────┐     ┌──────────────────┐
          │ 工具调用       │     │  文本回复          │
          │ ToolSearchTool│     │  (非 Markdown)    │
          │ (Milvus 向量) │     └────────┬─────────┘
          └──────┬───────┘               │
                 │                       ▼
          ┌──────▼───────┐     ┌──────────────────┐
          │ Milvus 搜索    │     │ 返回微信用户        │
          │ 稠密+稀疏向量  │     │ iLinkClient.sendText│
          └──────────────┘     │ /sendImage         │
                               │ /sendVoice         │
                               └──────────────────┘
```

---

## 第一阶段：登录与会话建立

### 1.1 扫码登录流程

```
Web 管理页面                WeChatAgentController        WeChatLoginServiceImpl        ILinkClientFactory        iLink SDK
    │                              │                            │                           │                       │
    │ GET /wechat/login/qrcode     │                            │                           │                       │
    │─────────────────────────────▶│                            │                           │                       │
    │                              │ createLogin()              │                           │                       │
    │                              │───────────────────────────▶│                           │                       │
    │                              │                            │ sessionId = UUID          │                       │
    │                              │                            │ createClient(sessionId)   │                       │
    │                              │                            │──────────────────────────▶│                       │
    │                              │                            │                           │ ILinkClient.builder() │
    │                              │                            │                           │  .onMessage(...)      │
    │                              │                            │                           │  .onHeartbeat(...)    │
    │                              │                            │                           │  .build()             │
    │                              │                            │                           │──────────────────────▶│
    │                              │                            │                           │                       │
    │                              │                            │ sessionRegistry.register()│                       │
    │                              │                            │ client.executeLogin()     │                       │
    │                              │                            │───────────────────────────────────────────────────▶│
    │                              │                            │                           │                       │
    │                              │                            │ client.getLoginFuture()   │                       │
    │                              │                            │  .whenComplete(           │                       │
    │                              │                            │    completeLogin(...))    │                       │
    │                              │                            │                           │                       │
    │  PNG 二维码 + sessionId      │                            │                           │                       │
    │◀─────────────────────────────│ LoginSessionResult         │                           │                       │
    │                              │◀───────────────────────────│                           │                       │
    │                              │                            │                           │                       │
    │  用户扫码 / 轮询状态          │                            │                           │                       │
    │──────────────────────────────────────────────────────────────────────────────────────────────────────────────▶│
```

### 1.2 登录成功后的会话激活

`WeChatLoginServiceImpl.completeLogin()` 完成以下操作：

1. **持久化登录状态** — `WeChatLoginStateRepository.save(loginContext)` 写入 MySQL，保证重启后无需重新扫码
2. **启动消息线程** — `UserThreadService.startSession(sessionId)` 为该会话创建虚拟线程
3. **注册用户映射** — `SessionRegistry.registerUser(userId, sessionId)`，供工具类（如 ReminderTool）通过 userId 查找客户端

### 1.3 应用重启恢复

`@EventListener(ApplicationReadyEvent.class)` 触发两个恢复流程：

| 恢复组件 | 方法 | 数据源 |
|---------|------|--------|
| 登录会话 | `WeChatLoginServiceImpl.restoreLoginSessions()` | MySQL `wechat_login_state` 表 |
| 定时提醒 | `ReminderServiceImpl.reloadOnStartup()` | Redis `westart:reminder:*` |

### 1.4 心跳与鉴权失效处理

SDK 内置心跳机制，`ILinkClientFactory` 注册 `OnHeartbeatListener`：
- **临时网络故障** — 保留登录状态，等待 SDK 后续心跳恢复
- **HTTP 401/403** — 判定为鉴权失效，清理会话 + 删除持久化凭证

---

## 第二阶段：消息接收与分发

### 2.1 SDK 消息回调

```
iLink SDK 轮询到新消息
    │
    ▼
ILinkClient.onMessage(messages)
    │  (ILinkClientFactory 在 buildClient 时绑定)
    ▼
UserThreadService.handleMessages(sessionId, messages)
    │
    ▼
从 SessionRegistry 获取 ILinkClientSession
    │
    ▼
逐条放入 session.messageQueue()  (ConcurrentLinkedQueue)
```

### 2.2 虚拟线程消息处理循环

`UserThreadServiceImpl.processSessionMessages()`:

```
while (会话有效 && 线程未中断) {
    message = queue.poll()
    if (message == null) {
        sleep(100ms)   // 空队列等待
        continue
    }
    
    userId = message.getFrom_user_id()
    
    // 批量收集队列中已积压的消息（不等待新消息）
    batch = collectAvailableMessages(queue, message)  // 最多 20 条
    
    // 开启微信「正在输入」状态
    client.startTyping(userId)
    
    // 委托给 UserMessageService 处理
    userMessageService.processMessageBatch(sessionId, userId, batch)
    
    // 关闭微信「正在输入」状态
    client.stopTyping(userId)
}
```

**关键设计**：
- **每条用户消息对应一个独立虚拟线程**（`Executors.newThreadPerTaskExecutor` + `Thread.ofVirtual()`）
- **串行处理**：同一会话的消息严格按顺序处理，避免并发回复错乱
- **批量收集**：`collectAvailableMessages()` 只取当前队列中已积压的消息（最多 20 条），不阻塞等待新消息

---

## 第三阶段：消息解析与 AI 调用

### 3.1 消息类型解析 (`UserMessageServiceImpl.buildUserMessage()`)

| 消息类型 | 处理方式 |
|---------|---------|
| **文本** (`text_item`) | 直接提取为 `TextContent` |
| **图片** (`image_item`) | `client.downloadImageFromMessageItem()` → Base64 → `ImageContent`（自动识别 JPEG/PNG/GIF/BMP/WebP） |
| **语音** (`voice_item`) | `item.getVoice_item().getText()` → 微信自动转写 → `TextContent` |
| **文件** (`file_item`) | 委托 `FileFormatTool.processIncomingFile()` 提取文本 → `TextContent` |
| **视频** (`video_item`) | **忽略**（不支持） |

### 3.2 多模态内容组装 (`prepareModelContents()`)

```
if (没有 TextContent) {
    prepend("请分析用户发送的图片并给出有帮助的回答。")  // 纯图片场景默认 Prompt
}
append(original contents...)                              // 用户消息
append("当当前可见工具无法回答用户的问题时，调用tool_search_tool搜索对应的工具。")  // 工具搜索提醒
```

### 3.3 AI 模型调用 (`WeChatAssistant`)

```java
@SystemMessage("# Role\n...")     // 详细的角色、语气、格式、工具使用策略系统提示
Result<String> reply(
    @MemoryId String memoryId,                // = from_user_id
    @V("memoryContext") String memoryContext, // 用户长期记忆上下文
    @UserMessage List<Content> contents       // 多模态用户内容
);
```

LangChain4j 框架自动处理：
- **短期记忆** — `RedisChatMemory` 提供 `TokenWindowChatMemory`，基于 Qwen 本地分词器估算 Token，超出上限时自动裁剪最早的消息
- **工具调用** — 模型可调用已注册的 8 个本地工具 + MCP 工具 + 工具搜索
- **工具搜索策略** — `ToolSearchTool` 在模型调用 `tool_search_tool` 时动态搜索工具

---

## 第四阶段：工具系统

### 4.1 已注册工具清单

| 工具类 | 工具名 | 能力 | 类型 |
|--------|--------|------|------|
| `WeatherTool` | `getWeather` / `getHourlyWeather` / ... | 天气查询 | 本地 |
| `LogisticsTool` | 物流查询相关 | 快递物流 | 本地 |
| `WebSearchTool` | 联网搜索 | 实时信息 | 本地 |
| `GaodeMapTool` | 地图相关 | 地址解析/路线规划/周边搜索 | 本地 |
| `ImageGenerateTool` | `generateImage` | AI 图片生成 | 本地 |
| `DailyHotTool` | `getDailyHot` | 每日热点新闻 | 本地 |
| `FileFormatTool` | `extractFileContent` / `convertFile` | 文件格式提取/转换 | 本地 |
| `ReminderTool` | `createReminder` / `createRepeatingReminder` / `cancelReminder` / `listReminders` | 定时提醒 | 本地 |
| `FoodOrderTool` | （暂时注释） | 餐饮点单 | 本地 |
| MCP 服务器 | 动态 | 外部工具服务 | MCP |

### 4.2 工具发现机制（Milvus 向量搜索）

```
应用启动 (ApplicationReadyEvent)
    │
    ▼
ToolSearchServiceImpl.initializeTools()
    │
    ├── 收集本地工具 (ToolRegistry.localTools()) → ToolEntity (LOCAL:xxx)
    ├── 收集 MCP 客户端 (ToolRegistry.mcpClients()) → ToolEntity (MCP:xxx)
    │
    ▼
embeddingModel.embedAll(descriptions)           // 1024 维稠密向量
    │
    ▼
toolEmbeddingStore.addAll(embeddings, entities)  // 写入 Milvus toolCollection
    │
    ▼
toolRepository.deleteInactiveTools(activeIds)    // 清理已下线的工具
```

**搜索流程**（AI 调用 `tool_search_tool(query="xxx")` 时）：

```
ToolSearchTool.search()
    │
    ▼
ToolSearchServiceImpl.searchTools(query)
    │
    ├── embeddingModel.embed(query)              // 查询文本 → 稠密向量
    ├── toolEmbeddingStore.search(request)       // Milvus 向量搜索 (maxResults=3)
    │     ├── 稠密向量 COSINE 相似度
    │     └── 稀疏向量 BM25 关键词匹配
    │
    ▼
返回匹配的 ToolEntity → 解析为实际可用的工具名称
```

**Milvus toolCollection 表结构**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | VarChar(32) | 主键，稳定 UUID（由 LOCAL:/MCP: 前缀计算） |
| `type` | VarChar(8) | LOCAL 或 MCP |
| `name` | VarChar(32) | 工具名 / MCP 客户端名 |
| `description` | VarChar(1536) | 工具描述（启用 BM25 分词） |
| `inputSchema` | JSON | 参数 Schema |
| `description_dense_vector` | FloatVector(1024) | 稠密向量 |
| `description_sparse_vector` | SparseFloatVector | BM25 稀疏向量（Milvus 内置 Function） |

### 4.3 工具调用防护 (`ToolCallGuard`)

`ToolCallGuard` 以 `ChatRequestTransformer` 方式注入，在每次模型请求前检测是否有工具重复调用，防止模型陷入无效的工具调用循环。最大往返次数上限为 10 (`maxToolCallingRoundTrips(10)`)。

---

## 第五阶段：记忆系统

### 5.1 短期记忆（对话上下文）

```
RedisChatMemory (实现 ChatMemoryStore)
    │
    ├── Key:  {redisKeyPrefix}{memoryId}   (memoryId = from_user_id)
    ├── Value: LangChain4j ChatMessage JSON 序列化
    ├── TTL:  {westart.memory.chat-ttl} 可配置
    │
    ▼
TokenWindowChatMemory
    ├── maxTokens: {westart.memory.max-tokens} 可配置
    ├── Tokenizer: Qwen 本地分词器 (QwenTokenizer)
    ├── 策略: 超出上限时从最早的非 System 消息开始裁剪
    └── 始终保留 SystemMessage 在首位
```

### 5.2 长期记忆（用户画像 — 异步处理）

用户消息**同步**进入 AI 对话后，**异步**通过 Redis Stream 进行画像分析：

```
UserMessageServiceImpl.processMessageBatch()
    │
    ├── 同步: chatHistoryService.publishUserMessage(memoryId, message)
    │         → 写入 Redis Stream
    │
    ├── 同步: chatHistoryThreadService.startUserProcessing(memoryId)
    │         → 启动该用户的记忆处理虚拟线程
    │
    └── 同步: memoryService.buildUserMemoryContext(memoryId)
              → 读取已有的 MySQL 长期记忆 → 注入 SystemMessage {{memoryContext}} 占位符
```

**异步记忆处理流水线** (`MemoryServiceImpl.processMemoryBatch()`):

```
Redis Stream 消费者拉取消息批次
    │
    ▼
Stage 1: 保存聊天历史
    chatHistoryService.saveMessageBatch(messages)
    │
    ▼
Stage 2: 模型筛选 (MemoryAssistant.filterUserProfileMessages)
    输入: 当前批次的全部消息 JSON
    输出: 标记为 "USER" 的消息列表 (仅保留 role=USER 且有画像价值的消息)
    │
    ▼
Stage 3: 画像总结 (MemoryAssistant.summarizeUserProfile)
    输入: 候选用户消息 + 已有的用户画像
    输出: 更新后的完整画像列表 (List<String>)
    │
    ▼
Stage 4: 画像同步 (synchronizeUserProfile)
    ├── 写入 MySQL user_memory 表 (upsert, memoryKey="user_profile")
    └── 标记 Stream 消息为已处理 (markMessagesMemoryProcessed)
```

**存储层**：
- **MySQL** `user_memory` 表 — 长期记忆持久化（按 `wechat_user_id` + `memory_key` 定位）
- **Milvus** `user_topic_memory` Collection — 用户主题记忆向量存储（备用）

### 5.3 Redis 数据全景

| Key 格式 | 用途 | TTL |
|----------|------|-----|
| `{prefix}{memoryId}` | 短期对话记忆 (TokenWindowChatMemory) | 可配置 |
| `chat_history:stream:{userId}` | 聊天历史 Redis Stream | — |
| `westart:reminder:{uuid}` | 定时提醒任务 | 一次性: delay+5min; 周期: 无 |
| `wechat:login:state:{userId}` | 微信登录持久化状态 | — |

---

## 第六阶段：回复投递

### 6.1 回复策略

| 场景 | 投递方式 |
|------|---------|
| **纯文本回复** | `client.sendText(userId, content)` |
| **纯图片回复**（无文本） | `client.sendImage(userId, bytes, fileName)` — 图片生成工具的结果 |
| **语音输入 → 语音回复** | `voiceGenerateService.generateAndSendVoice(client, userId, content)` — TTS 合成 |
| **失败兜底** | `client.sendText(userId, "消息处理失败，请稍后重试。")` |

### 6.2 生成图片处理 (`sendGeneratedImages()`)

遍历 `result.toolExecutions()`，筛选 `generateImage` 工具的成功执行结果：
- 优先使用 Base64 数据解码
- 否则通过 URL 下载 (OkHttp)
- 逐张通过 `client.sendImage()` 发送

### 6.3 定时提醒消息投递

`ReminderServiceImpl.fireReminder(reminderId)`:
```
从 Redis 读取 ReminderTask
    → sessionRegistry.findClientByUserId(task.userId())  // 查找在线客户端
    → client.sendText(userId, "⏰ " + task.message())    // 发送提醒
    → 一次性提醒自动删除 Redis Key + 移除 ScheduledFuture
```

---

## 第七阶段：会话生命周期管理

### 7.1 会话注册表 (`ILinkClientSessionRegistry`)

| 数据结构 | Key | Value | 用途 |
|---------|-----|-------|------|
| `sessionMap` | sessionId | `ILinkClientSession` | 会话查询与关闭 |
| `userSessionMap` | userId | sessionId | 工具类按用户查客户端（如 ReminderTool） |

### 7.2 会话关闭流程

```
logout(sessionId)
    │
    ├── userThreadService.stopSession(sessionId)     // 取消虚拟线程
    ├── sessionRegistry.closeAndRemove(sessionId)    // 清空队列 + 关闭 client
    │     ├── messageQueue.clear()
    │     ├── client.cancelLogin()
    │     └── client.close()
    └── loginStateRepository.deleteByUserId(userId)  // 删除持久化凭证
```

### 7.3 应用关闭 (`@PreDestroy`)

| 组件 | 清理动作 |
|------|---------|
| `UserThreadServiceImpl.destroy()` | 取消所有会话的虚拟线程 |
| `ILinkClientSessionRegistry.closeAll()` | 依次关闭所有 iLink 客户端会话 |

---

## 附录 A：关键线程模型

```
main
  │
  ├── iLink SDK 内部线程 (Netty)
  │     └── 消息轮询 + onMessage 回调
  │
  ├── wechat-user-message-* (虚拟线程, 每个会话一条)
  │     └── UserThreadServiceImpl.processSessionMessages()
  │
  ├── chat-history-thread-* (虚拟线程, 每个用户一条)
  │     └── ChatHistoryThreadService: Redis Stream 消费 + 长期记忆处理
  │
  ├── TaskScheduler 线程池 (size=4)
  │     └── ReminderService: 定时提醒触发
  │
  └── ExecutorService (wechatUserMessageExecutor)
        └── Thread.ofVirtual().factory()
```

## 附录 B：配置项

| 配置键 | 说明 | 默认值/示例 |
|--------|------|------------|
| `westart.memory.max-tokens` | 短期记忆最大 Token 数 | — |
| `westart.memory.redis-key-prefix` | Redis 记忆 Key 前缀 | — |
| `westart.memory.chat-ttl` | 短期记忆 TTL | — |
| `westart.memory.wechat.message.batch-size` | 用户消息批次大小 | — |
| `westart.memory.wechat.message.batch-timeout` | 用户消息批次超时 (秒) | — |
| Milvus URI | Milvus 连接地址 | `http://127.0.0.1:19530` |

## 附录 C：关键类索引

| 类 | 文件路径 | 职责 |
|----|---------|------|
| `WeChatAgentController` | `controller/WeChatAgentController.java` | Web 管理端 REST API |
| `WeChatLoginServiceImpl` | `service/impl/WeChatLoginServiceImpl.java` | 扫码登录、会话恢复、心跳处理 |
| `ILinkClientFactory` | `config/ILinkClientFactory.java` | 创建 iLink 客户端，绑定消息和心跳回调 |
| `UserThreadServiceImpl` | `service/impl/UserThreadServiceImpl.java` | 虚拟线程消息分发、批量收集 |
| `UserMessageServiceImpl` | `service/impl/UserMessageServiceImpl.java` | 消息解析、AI 调用、回复投递 |
| `WeChatAssistant` | `service/ai/WeChatAssistant.java` | LangChain4j AI 服务接口 |
| `AiServiceConfig` | `config/AiServiceConfig.java` | AI 服务 Bean 装配（工具、记忆、策略） |
| `ToolSearchTool` | `service/tool/ToolSearchTool.java` | 动态工具搜索策略（Milvus 向量匹配） |
| `ToolSearchServiceImpl` | `service/impl/ToolSearchServiceImpl.java` | 工具向量初始化与搜索 |
| `RedisChatMemory` | `config/RedisChatMemory.java` | Redis 短期记忆存储 |
| `ChatMemoryConfig` | `config/ChatMemoryConfig.java` | Token 窗口记忆配置 |
| `MemoryServiceImpl` | `service/impl/MemoryServiceImpl.java` | 长期记忆分析、总结、同步 |
| `MemoryAssistant` | `service/ai/MemoryAssistant.java` | 记忆分析 AI 接口 |
| `ReminderServiceImpl` | `service/impl/ReminderServiceImpl.java` | 定时提醒核心逻辑 |
| `ReminderTool` | `service/tool/ReminderTool.java` | 提醒工具 AI 接口 |
| `ILinkClientSessionRegistry` | `service/impl/ILinkClientSessionRegistry.java` | 会话注册表与用户映射 |
| `ILinkClientSession` | `DTO/ILinkClientSession.java` | 会话数据结构 |
| `MilvusConfig` | `config/MilvusConfig.java` | Milvus 数据库和集合初始化 |
| `AssistantListener` | `listener/AssistantListener.java` | 模型调用日志监听器 |
| `ExecutorConfig` | `config/ExecutorConfig.java` | 虚拟线程执行器配置 |
| `SchedulerConfig` | `config/SchedulerConfig.java` | TaskScheduler 定时任务线程池 |
