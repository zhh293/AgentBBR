# 实现设计与契约

状态：设计基线 v1；按实现证据持续修订。源架构 `../loglens-architecture.md`。

## 模块依赖

`api` 仅 JDK；`bootstrap` 仅 JDK。`config` → api + SnakeYAML；`processors` → api；`storage` → api/config + Jackson/SQLite；`web` → storage/config + JDK HttpServer；`runtime` → api/config/processors/storage/web + Disruptor；`adapter-logback` → api/config/bootstrap/runtime（用反射立即快照不同应用 loader 的事件）；`agent` → runtime/adapter/bootstrap + Byte Buddy；`viewer` → storage/web。`ui` 提供纯 HTML/CSS/JS 资源，嵌入 web，无远程资源。

构建为 Maven reactor，Java release 17。固定依赖版本，不使用动态版本。Agent 外壳仅 premain/隔离加载器；将 shaded runtime JAR 与 bootstrap bridge JAR 作为嵌套资源。外壳把 bridge 加入 bootstrap search，runtime 用 platform parent 的 URLClassLoader 加载，应用类只能引用 bridge 的 JDK 类型。API 只有运行时一份；插件 loader API 父优先，其他类可自行加载，禁止插件 JAR 打包 API 类。

premain 参数为 `config=<path>`，可追加 `,strict=true` 用于配置本身无法解析时明确要求终止；路径中的逗号通过 config 占据参数尾部解决（strict 在 config 前）。外壳提取资源到 Agent JAR 同目录 `.loglens-runtime/<UUID>`，不使用默认系统 tmp。隔离加载器 platform 父级仍能看到 bootstrap CaptureBridge。嵌套资源名为 `loglens-bootstrap.jar` 和 `loglens-runtime.jar`；打包检查必须验证此布局。默认启动失败诊断后应用继续，strict 参数失败则抛出。

## 事件与 SPI

- CapturedLog 是不可变 record，字段包含 sessionId/eventId/captureSequence、eventTime/capturedAt、monotonicNanos、level/logger/thread、template/message、parameters、MDC、exception/caller、truncated。ExceptionSnapshot 只含字符串/字符串列表。
- LogContext 是消费者私有可复制状态，含不可变 captured、fields/tags/decisions、汇总计数/首尾时间/SourceRefs。链入口和输出分别创建快照。
- SourceRefs 用组键/时间和首尾 sequence 查询持久化明细，不在内存保留全部 ID；有限样本单独保存。分组键为各字段长度前缀编码，避免拼接碰撞。
- NodeDependencies 提供 SourceIndex 回调，在 RepeatFold 接收事件时为 captured 索引登记实际语义分组（含归一化模板与提取字段）。来源查询按实际分组与 sequence 范围，避免原模板和归一化模板不一致导致漏明细。
- ProcessedRecord 包含独立 recordSequence/id、FINAL 状态、类型、上下文快照和源引用。
- LogProcessor/Factory/ChainProvider/Continuation/Emitter 按架构名字发布；ChainBuildContext 含已加载 pipeline 定义，NodeDependencies 含可控双时钟与独立诊断接口。
- process continuation 只允许同步、当前消费者线程、调用一次。flush emitter 同样限定调用期间和线程，每次从当前节点的下一个位置进入。失败使用进入节点前的副本，避免失败中间状态泄漏或重复链。
- 未调用 continuation 的正常返回必须记录该节点的 DROP 或 BUFFER 决策。默认视为 DROP 并诊断缺失原因；RepeatFold 显式 BUFFER，避免暂存计入永久丢弃。运行时 processorDropped 包含正常丢弃与异常 DROP。
- LogProcessor 增加默认 beforeProcess(FlushContext, LogContext, EventEmitter) 回调。运行时在该节点 process 前调用，用于输入触发的过期/容量 flush。这样 flush 可输出多条，process 仍严格最多 proceed 一次；不把容量驱逐伪装为多次 continuation。

## 配置

根配置覆盖架构 YAML。每层严格白名单，节点 config 由对应工厂额外校验（未知插件参数归插件工厂负责）。重复 YAML key 拒绝；禁止任意对象构造与 YAML alias 放大。所有数字有上限，application 禁止路径分隔符。端口 0 可用于测试动态分配，生产示例 9527。web.host 仅 loopback。pipeline nodeId/provider 非空且唯一 ID；内置节点相对次序校验，用户整链有自主顺序但必须通过工厂和依赖校验。

配置用嵌套不可变 record、递归不可变 node config；预编译 Pattern 保存在运行时规则对象。configHash 为原 YAML UTF-8 字节 SHA-256；API 摘要只展示配置结构、节点 ID/provider、数值设置与规则 ID，不返回敏感表达式/replacement。所有变更必须重启。

配置扩展：capture 增加 maxMdcEntries/maxMdcValueChars/maxParameterChars/maxParameters/maxExceptionChars；runtime 增加 processorFailurePolicy=OUTPUT_FAILED|DROP、slowProcessorMillis；web 增加 maxClients/maxQueryLimit；这些字段有默认值，以满足架构有界要求。STRICT 在 agent.failurePolicy 中选择。

内置节点 config 契约：FieldExtract 为 rules[{field,pattern,group=value}] 与 mdcMapping{目标字段: MDC键}；Template 为 preferOriginalTemplate=true 和 replacements[{id,pattern,replacement}]；RepeatFold 为 windowMillis/maxGroups/maxSamplesPerGroup/bypassLevels/groupBy；Correlation 为 fields；Highlight 为 firstOccurrence/maxSeenTemplates/keywords。每个工厂拒绝未知参数，正则/替换引用和范围在 create 时校验。fields/tags/decisions 最终输出有运行时大小上限，插件不得绕过外围脱敏。

## 捕获和接管

Byte Buddy 对 Logback Logger.callAppenders 入口/出口插入 bridge enter/exit，不替代整个方法。入口立即快照并发布，bridge ThreadLocal 保存本次是否成功及目标名称；仅对直接 ConsoleAppender 的 doAppend 分支 Advice skip。检测包装 AsyncAppender 含目标时整个事件以 observe 回退并报告。原 Appender 的过滤器仍负责自身输出，TurboFilter/Logger level 由原 Logger 在 callAppenders 之前执行。Appenders 的不同输出过滤器无法定义统一全局日志级别，以事件分发边界为采集语义。

捕获范围检查在快照/申请槽位前；桥有 finally 清理重入；快照始终当前应用日志线程；不持久化/缓存原事件/Throwable/参数对象；诊断避免输出未脱敏消息。接管 active 同时要求成功发布、存储健康、运行时未停止。reset 后 Advice 仍作用新 ConsoleAppender；版本/结构不兼容诊断并禁用捕获。

## 消费、折叠和生命周期

Disruptor MULTI + BlockingWaitStrategy，线程 daemon；输入 LOG 已脱敏，消费者先 captured 再 process；TICK/STOP 只触发 runtime flush。申请槽位 finally 发布 INVALID 兜底，消费 finally clear。DROP_NEWEST 用 tryNext；BLOCK 明确阻塞，停机时禁止新生产者进入并等待在途生产者有限完成。

RepeatFold 用 LinkedHashMap 保存有界组，单调时钟固定窗口，每组一个独立摘要和有限样本；后续日志先刷新到期组，TICK 刷到期，STOP 全刷；CAPACITY 刷最早组；ERROR 不暂存。来源在 captured 索引重建可获得。首尾时间由捕获墙钟展示，不用墙钟判断窗口期限。

停机先 disable bridge，停止 tick，消费者 STOP flush 后关闭节点/存储；协调线程有限排空，超时 metadata incomplete，不能与写入并发关文件。初始化资源按逆序清理；默认 premain 捕获失败并诊断，STRICT 抛出终止。磁盘/索引错误置 output unhealthy，立即 disable takeover，消费者不建立内存重试队列。

## 存储和查询

SQLite 存索引和 source group/query 字段，JSONL 是事实依据。UTF-8 byte offset 和 length；文件追加 → 索引事务 → SSE。text 精确等级匹配。按 UTC 日期或字节阈值滚动；仅关闭 segments 可删除，同时删除索引位置引用。历史目录只读查询，不修改旧 index；缺索引/损坏索引使用工作区临时可重建索引。重建逐行流式扫描，末尾不完整行忽略并标记恢复状态，不能伪造旧 FINAL。

会话元数据 segments 为现存滚动日志/JSONL 文件清单，含 file/bytes/active；会话结束后 active=false。查询实时元数据扫描当前会话目录，结束写入 session.json 固定清单；片段清理后不保留已删除文件引用。

为完整重建 normalized template / extracted field 的 source_groups，追加 `groups-NNNN.jsonl` 语义分组日志，字段为 captureSequence/groupBy/groupValues。其写入先于组索引，属于结构化 segment；删除组片段同步清除关联索引，使无法恢复的来源显式标过期。 Source group security invariant: apply the same configured-key masking and message redaction to groupBy/groupValues before writing groups JSONL or SQLite and before persisting FINAL SourceRefs, so the query key remains consistent and raw plugin-derived values never reach disk.

SQLite native DLL 使用显式配置的 E 盘 `.build/native`（测试）或输出根 `.native`（运行），不放在会话或单次恢复目录中。Windows 在 JVM 退出前锁定已加载 DLL，避免它阻止会话删除或只读恢复目录清理；任何原生解压都不使用默认 C 盘 tmp。

records 用 recordSequence 的 keyset cursor，limit 1..配置上限；level/logger/tag/traceId/orderId/q 同步过滤，不使用 OFFSET 深分页。sources 分页用 captureSequence 游标与持久化组键/范围过滤。JSONL 行/导出/HTTP 请求有大小上限。

## Web/UI/viewer

HTTP bind loopback，固定 bounded executor 与连接上限；GET only、无 CORS、Host/Origin 检查；SSE 每客户端 bounded queue + 水位 handshake，连接建立后发送 ready 水位，客户端查历史 <= 水位、合并实时序号去重。断线查询 > lastSequence 补齐，gap 文字提示。客户端缓慢队列满则 disconnect/gap，不阻塞 append。

运行时创建 EventHub 并将存储成功广播接入，再启动 LocalWebServer；端口冲突须关闭消费者、存储与插件后传播初始化失败。退出时停止采集并排空，最后关闭 HTTP 和订阅。web.enabled=false 不创建服务；实际动态端口通过 runtime.webPort 暴露给集成测试。

UI 工业仪表台风格：炭黑/暖白双主题、琥珀强调、等宽日志、紧凑侧栏和细分隔线。虚拟列表固定行高，载入数据数量有上限，历史游标翻页；详情全部 textContent。URLSearchParams 存筛选；暂停只暂停自动滚动。viewer 复用 web，离线导出把 JSON 的 `<`/`>`/`&` 等编码，纯文本呈现，无外部资源、限制行数和字节数。

键盘可达控件保留原生按钮/表单语义；虚拟日志行使用 button role、可见名称和 Tab 焦点，Enter/Space 打开详情。按钮、输入框、选择框和日志行在 `:focus-visible` 下显示主题强调色轮廓。浏览器验收以真实 Chrome 键盘事件验证焦点、激活与详情状态。

## 验证设计

JUnit 5 单元测试：配置错误矩阵/不变性/路径、可控时钟窗口、责任链异常/continuation、插件冲突、segment 滚动/保留/恢复、来源与游标。真实子 JVM：Agent 打包、无 API 污染、多 Appender/reset/过滤器、捕获与原日志结果、Java 17/21、Spring Boot nested JAR + 外部插件。HTTP/SSE 集成：水位、溢出、查询限制和历史只读。浏览器自动化：虚拟列表、详情、主题、关注、暂停、安全导出。性能固定种子对照三模式，所有数据标环境与样本规模。

## 决策记录

| ADR | 决策 | 理由 |
|---|---|---|
| D01 | 先落规格、设计、任务与验证模板 | 用户明确要求严格 SDD，仓库没有既定流程 |
| D02 | Maven 缓存/临时/用户设置均工作区隔离 | 不写 C 盘，使用 IDEA 工具 |
| D03 | 反射 Logback 适配器 + bootstrap bridge | 适配应用自带框架及 Spring Boot loader，避免双份 Logback |
| D04 | 嵌套隔离 runtime 及独立 bridge | 应用依赖隔离；只共享 JDK callback |
| D05 | 显式有界配置与 keyset 查询 | 满足长消息、十万行、慢连接的内存上限 |
| D06 | `package-release.ps1` 统一构建后复制 Agent、Viewer 与 API JAR，并检查入口/manifest | 减少手动发布遗漏；测试由发布前单独的 `verify` 门禁负责 |
| D07 | 基准程序固定 seed=20261012、100,000 次参数化日志、64 logger buckets，对照 baseline/observe/replace-console；不设想定性能阈值 | 记录实测吞吐、调用分位数、进程 CPU、GC、采样峰值堆、磁盘字节、丢弃与接管回退，再据此制定目标 |
| D08 | 将日志行详情和核心操作纳入键盘可访问验收 | R17 的虚拟行是自绘交互控件；只有鼠标处理会使键盘用户无法查看详情，也无法辨认当前焦点。补充原生 button 语义、Enter/Space 激活与可见焦点，避免改变数据/查询契约 |
