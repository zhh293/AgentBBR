# 实施任务与追踪

状态必须由实际文件/验证证据更新。`[ ]` 待实施；`[~]` 实施中；`[x]` 完成且有验证。

| 任务 | 状态 | 依赖 | 内容 | 需求 |
|---|---|---|---|---|
| T00 | [x] | — | SDD 基线、约定、设计、验收追踪 | 全部 |
| T01 | [~] | T00 | IDEA 工具链、E 盘缓存、Maven reactor 与构建脚本 | R21,R24 |
| T02 | [~] | T01 | JDK-only API、不可变模型、SPI 与 bootstrap bridge | R04,R05,R07,R08 |
| T03 | [~] | T02 | YAML validation, immutable config and security summaries; plugin-derived field/source-index redaction is now covered end-to-end, remaining redaction channels need audit | R11,R12 |
| T04 | [~] | T03 | 默认处理器、可控时钟折叠与有界状态；新增处理器规则边界测试已通过，默认生产链组合仍待最终验收 | R08,R09,R10 |
| T05 | [~] | T03 | SPI loader/冲突检查与链运行时 | R05,R07,R08 |
| T06 | [~] | T04,T05 | 结构化/文本/SQLite、segment 保留与恢复查询；captured/index 故障和 processed 事务回滚后 JSONL 历史重建已验证 | R13,R14 |
| T07 | [~] | T06 | MULTI/BLOCK saturation, blocked-publisher exit, STOP drain, storage-stage and web-stage reverse cleanup (including cleanup callback failure), SQLite partial-transaction fallback, and stuck-plugin deferred cleanup verified; other init phases, other persistent faults and forced-kill behavior pending | R06,R19,R20 |
| T08 | [~] | T07 | Agent 打包/真实 Logback/Boot/直接控制台/reset 已有阶段证据；Java17/复杂 Appender/故障与 loader 生命周期待验收 | R01–R05,R12 |
| T09 | [~] | T06,T07 | HTTP/SSE runtime integration, bounded client recovery and real non-reading TCP slow-client isolation verified; publisher timing excludes deliberate 5 ms pacing; browser reconnect/deduplication across concurrent history batches pending | R15,R16 |
| T10 | [~] | T09 | UI 虚拟流/详情/筛选/主题/关注/暂停；键盘语义、Enter/Space 与焦点样式已实现，新增浏览器验收待运行 | R17 |
| T11 | [~] | T09,T10 | 历史 viewer、恢复/导出/不完整会话展示 | R14,R18 |
| T12 | [~] | T08,T11 | Java/Spring Boot/demo、两种插件、运行说明 | R01,R07,R21 |
| T13 | [~] | T12 | 全栈兼容性、故障测试与安全测试 | R01–R20 |
| T14 | [~] | T13 | Three fixed-seed 100k performance runs; 100k history recovery and all 100 pages verified; variance control and performance target remain | R14,R22 |
| T15 | [~] | 全部 | 最终逐需求审计、验证报告、交付文档 | 全部 |

## 阶段门禁

- G1 已建立规格/设计/任务文件，后续实现须对应任务。
- G2–G7 尚未验证。不得标为完成。

详细未完成项与证据边界见 [逐需求审计](audit.md)，不得仅依据已有测试数量勾选任务。
