# P2-5 基础可观测增强任务卡

## 目标

在本地学习项目边界内增强运行统计，让用户能看清 provider/model、tokens、成本、工具调用和失败分布。

## 依赖

- 必须等待 P2-4 完成。

## 必做能力

- 本地统计服务。
- 按 provider/model 统计 turn 数、tokens、成本、失败次数。
- 按工具统计调用次数、失败次数、耗时。
- 后端 JSON 日志继续保持可定位 thread、turn、provider、cwd、sandbox、approval、tool。
- 可选引入 Actuator + Micrometer，但不接 Langfuse、OpenTelemetry UI、Prometheus 部署。

## 验收

- 能通过 JSON-RPC 或轻量接口查询本地统计快照。
- 统计结果来自持久化运行记录，不依赖当前内存。

## 下一步

在实现前写 `docs/superpowers/plans/p2-5-local-observability/plan.md` 并等待用户确认。
