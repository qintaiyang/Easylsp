---
name: develop-hook
description: 在项目中编写、修改、构建和运行 Java/BeanShell 或 Native C 插件，并根据实际状态和日志验证用户要求的效果。
---

# 开发与验证插件

按照目标选择 Java 或 Native 工作方式；使用真实成员签名、样本和进程信息。需要 API 细节时 read_development_reference 获取 plugin-api 或 native-api／native-header 及相关例子，不把全部手册装进每次任务。

保存源码只产生新 revision，不自动改变运行实例：

- Java：按任务加载／重载／卸载，目标进程来自实际 session。get_plugin_status 和日志反映实际加载 revision；预期启用状态与正在运行状态分别读取。
- Native：build_native_plugin 固定 sourceRevision，读取同一 executionId 的构建输出；enable_native_plugin 发布成功的固定 buildId 和明确策略。更新／停用在目标下次启动生效，不宣称热卸载。
- 需要多文件、依赖或导出时使用现有插件文件、库和导出工具；不因预设没有列出完整过程放弃能力。

选择工具组只是便利；模型可以直接加载目标、检视、终端、诊断或子代理工具。启动采集、重启应用等动作根据实际任务需要执行，不把每次修改都变成全套诊断。

构建成功、插件初始化和 Hook 命中是不同证据；用户要求的效果以实际行为核验。读取相关日志或诊断原始数据定位问题，避免用“没有证据”断言“没有执行”。
