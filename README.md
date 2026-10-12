# LogLens：Java 本地日志工作台

通过 Java Agent 与 Byte Buddy 接入日志，使用 Disruptor 缓冲事件，通过 SPI 定制责任链，由本地 YAML 驱动规则。处理结果按等级保存，并在本地网页中展示。

当前处于设计阶段，尚未实现运行代码。LogLens 为暂定名称。

- [详细架构设计](docs/loglens-architecture.md)

设计接入方式：

```bash
java -javaagent:loglens-agent.jar=config=./loglens.yaml -jar app.jar
```
