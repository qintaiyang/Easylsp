# Easylsp

**更容易、更简单地开发模块。**

Easylsp 是面向 Android 的开源模块开发与调试工具。用自然语言描述目标，让 AI 分析应用、编写插件、构建运行并查看实际结果；也可以使用编辑器和调试工具手动开发。

首个开源版本：**0.8**。项目采用 [GPL-3.0-or-later](LICENSE)，第三方组件及许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 功能

- **AI 助手**：支持 OpenAI 兼容、Anthropic 和 Gemini 协议；支持快速对话、项目会话、附件、历史分支、任务用量与工具授权。
- **Java Hook**：BeanShell 插件，支持 before / after / replace、保存版本、加载、重载、卸载及日志。
- **Native C 插件**：在手机 Ubuntu 环境编译 Android SO，支持符号与 ELF 虚拟地址 Hook、模块生命周期观测和启动诊断。
- **应用分析**：查询类、方法、字段、字符串及引用，查看 Java、Smali、Manifest 和资源。
- **运行检视**：查看目标进程、View 树、创建堆栈与性能数据。
- **工作台与扩展**：真实 PTY 终端、Ubuntu chroot、MCP、Skill、工具插件、记忆与手机操作。
- **独立模块导出**：将 Java 插件导出为独立 APK。

## 环境要求

- Android 14（API 34）及以上，arm64-v8a。
- 动态 Hook 需要支持现代 libxposed API 101+ 的框架，在管理器中启用模块并授予目标应用作用域。
- Ubuntu、终端与部分设备工具需要 root；手机操作需要系统无障碍权限。
- AI 功能需要自行配置模型供应商和 API Key；静态分析可独立使用。

## 开始使用

1. 安装 Easylsp。需要 Hook 时，在 LSPosed 中启用模块、勾选目标应用并重启目标。
2. 在“设置 → 执行环境”准备 Ubuntu 和 Harness。
3. 在模型设置中选择供应商、填写 API Key 并选择模型，设置上下文容量与工作预算。
4. 使用“快速对话”处理通用任务；开发模块时添加目标应用或导入 APK，再进入项目会话。
5. 描述想实现的效果，选择当前会话的运行授权，让 AI 完成分析、编写、执行和结果核对。

输入 `/help` 查看命令，`/tools` 与 `/skills` 管理会话能力。MCP 和扩展位于“设置 → 扩展与工具”。离开页面后可通过前台通知或 EL 悬浮圈返回任务。

手动开发可进入“高级工具”。保存源码会创建新版本，加载或重载后才改变运行实例。Native 插件发布、更新或停用在目标下次启动时生效。

## 构建

需要 JDK 21、Android SDK Platform 35 / 36、Build Tools 35.0.0 / 36.0.0、NDK 28.2.13676358、CMake 3.22.1。

```sh
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug :demo-target:assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk` 和 `demo-target/build/outputs/apk/debug/demo-target-debug.apk`。演示应用提供可直接验证的 Hook 目标。Harness 使用 Node.js 24.11.0，依赖由 `harness/package-lock.json` 固定。

```sh
./gradlew :app:testDebugUnitTest :hook-runtime:testDebugUnitTest :demo-target:testDebugUnitTest
python3 scripts/verify-skill-migration.py
```

## 插件与示例

- [Java 插件 API](app/src/main/assets/plugin-api.md)与[示例](examples/plugins/)
- [Native 插件 SDK](app/src/main/assets/native-sdk/native-plugin-api.md)与[示例](examples/native-plugins/)
- [Skill 示例](examples/skills/README.md)与[工具插件示例](examples/tool-plugins/echo.java)
- [版本记录](CHANGELOG.md)

Native 插件当前面向 C / arm64-v8a，不支持 C++ / STL 和 Native 独立 APK 导出。各功能的实际可用状态取决于框架、目标进程、设备权限及已配置的执行环境。
