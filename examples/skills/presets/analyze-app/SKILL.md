---
name: analyze-app
description: 在项目会话中检查 APK Manifest、资源、DEX 类和成员引用，读取 Java/Smali 并形成有样本依据的分析结论。
---

# 分析应用样本

从当前绑定的项目和 sampleRevision 获取实际样本信息，按问题定位相关成员和资源。使用返回的完整 DEX 签名、参数类型及 APK/DEX 来源，不凭名称猜 Hook 签名。

查询、反编译和资源结果支持分页，所需证据可以完整展开。Java 输出标记反编译错误时用实际 Smali 核对相关行为；静态路径不是运行时已经执行的证据。

capture_sample 会产生新的 revision，不会改变本会话既有绑定。需要分析新样本时使用返回的 revision 调用 analysis_query，不能混合不同版本的结论。

Native 问题可直接加载相关工具，必要时准备离线 LLVM 工具并检查实际 SO、符号和地址来源。需要动态验证时使用目标／诊断能力或另行组织子任务，不因本 Skill 的建议列表阻断后续工作。

快速对话没有项目样本；用户需要 APK 分析时进入对应项目或创建项目分支，不能虚构项目身份。
