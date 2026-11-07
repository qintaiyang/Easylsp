# Easylsp

**更容易、更简单地开发模块。**

Easylsp 是面向 Android 的开源模块开发与调试工具。用自然语言描述目标，让 AI 分析应用、编写 Java／Native 插件、构建运行并查看结果，也可以使用编辑器和调试工具手动开发。

当前版本：**0.91**。项目采用 [GPL-3.0-or-later](LICENSE)，第三方组件及许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 功能

- **首页**：显示工作状态、作用应用与已启用插件数量，以及管理器版本、引擎版本和设备型号。点击“应用”搜索并勾选作用应用，通过 libxposed 请求或移除框架作用域；点击“插件”查看已启用的 Java／Native 插件。
- **AI 助手**：支持 OpenAI 兼容、Anthropic 和 Gemini 协议，提供项目会话、附件、历史分支、任务用量和工具授权。
- **Java 插件**：BeanShell Hook，支持 before／after／replace、版本保存、加载、重载、卸载及日志。
- **Native 插件**：支持 C17／C++17、静态 STL、符号与地址 Hook、CHAIN、JNI 注册、ARM64 指令拦截、调用方 PLT 和运行中注册／解除，可选择 Zygisk 或 libxposed 后端并与 Java 插件联合使用。
- **插件市场**：签名目录、固定预编译发行、配置、更新、回退、联合发行与固定依赖；AI 可使用作者账号创建 GitHub 仓库、提交指定源码、上传 Release 和提交市场收录申请。
- **应用分析**：查询类、方法、字段、字符串及引用，查看 Java、Smali、Manifest 和资源，检视目标进程与 View 树。
- **工作台与扩展**：PTY 终端、Ubuntu chroot、MCP、Skill、工具插件、记忆与手机操作。
- **后台运行**：root 授权后自动配置 UID0 保活服务，可在执行环境中关闭或修复；提供小米、一加、真我的系统后台设置入口。管理器应用保留 Android 分配的 UID。
- **独立模块导出**：将 Java 插件导出为独立 APK。

## 环境要求

- Android 12（API 31）及以上，arm64-v8a。
- Hook 工作流需要 root 及支持现代 libxposed API 101+ 的框架；在框架中启用 Easylsp 并批准目标作用域。
- AI 功能需要自行配置模型供应商和 API Key；手机操作另需系统无障碍权限。
- Native 手机编译需要在执行环境中准备 Ubuntu；Native 宿主通过现有 root 管理器安装。

Android 12／Vector 与 Android 16／LSPosed 已进行设备验证；Android 12 的作用域申请另经手动测试通过。设备验证覆盖 4KB 页面和 Zygisk Next 1.4.3，不代表其他提供者或 16KB 页面均已验证。当前 Next 817 下 app zygote 子进程和 WebView renderer 的 Native 初始化未通过验证。小米、一加的后台设置适配尚无对应真机验证。

## 开始使用

1. 安装 Easylsp，在框架管理器中启用模块。
2. 点击首页“应用”，勾选目标并在框架提示中确认作用域；修改后重启目标应用。
3. 在“设置 → 执行环境”准备所需的 Ubuntu、Harness 和 Native 宿主。宿主安装或升级后重启手机，插件发布、更新或停用后重启目标应用。
4. 在模型设置中填写供应商、API Key 和模型，设置上下文容量与工作预算。
5. 在项目中添加目标应用或导入 APK，进入会话描述目标并选择运行授权。通用任务可使用独立聊天会话。

输入 `/help` 查看命令，使用 `/tools` 与 `/skills` 管理会话能力。MCP 和扩展位于“设置 → 扩展与工具”。手动开发可进入高级工具，保存源码后显式加载或发布。

## 构建与验证

需要 JDK 21、Android SDK Platform 35／36、Build Tools 35.0.0／36.0.0、NDK 28.2.13676358 和 CMake 3.22.1。

```sh
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug :demo-target:assembleDebug :zygisk-host:assembleHost
```

管理器 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，演示应用位于 `demo-target/build/outputs/apk/debug/demo-target-debug.apk`，Native 宿主 ZIP 位于 `zygisk-host/build/outputs/`。Harness 使用 Node.js 24.11.0，依赖由 `harness/package-lock.json` 固定。

```sh
./gradlew :app:testDebugUnitTest :hook-runtime:testDebugUnitTest :demo-target:testDebugUnitTest :app:lintDebug
python3 scripts/verify-skill-migration.py
python3 scripts/test-host-protocol.py
python3 scripts/test-native-core.py
python3 scripts/test-market.py
python3 scripts/test-github-market.py
```

设备测试需要安装管理器、演示应用与 `:app:assembleDebugAndroidTest` 生成的测试 APK，并准备对应框架、root 和作用域。测试源码位于 `app/src/androidTest/`；CI 构建配置见 [.github/workflows/android.yml](.github/workflows/android.yml)。

正式签名使用 `-PeasylspReleaseSigning=/path/to/release.properties`，配置文件包含 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword`，由本地维护，不加入仓库。

主分支 GitHub Actions 使用仓库 Secrets 中保存的固定签名生成 debug 和 release APK：`EASYLSP_DEBUG_KEYSTORE`、`EASYLSP_RELEASE_KEYSTORE`、`EASYLSP_RELEASE_PROPERTIES` 均为对应文件的 Base64 内容。PR 构建不读取这些签名。升级时选择与已安装版本相同的 debug／release 包；同一签名系列可覆盖安装并保留数据，两种签名不能互相覆盖。

## 插件、市场与示例

- [Java 插件 API](app/src/main/assets/plugin-api.md)与[示例](examples/plugins/)
- [Native 插件 SDK](app/src/main/assets/native-sdk/native-plugin-api.md)与[示例](examples/native-plugins/)
- [插件发行格式](app/src/main/assets/plugin-release.md)与[市场协议](market/PROTOCOL.md)
- [Skill 示例](examples/skills/README.md)与[工具插件示例](examples/tool-plugins/echo.java)
- [版本记录](CHANGELOG.md)

官方市场：[Easylsp-Market](https://github.com/qintaiyang/Easylsp-Market) · [匿名目录](https://qintaiyang.github.io/Easylsp-Market/) · [作者示例](https://github.com/qintaiyang/Easylsp-Plugins)。只读安装不需要登录。作者可显式配置 GitHub 凭据；OAuth Device Flow 需先配置自己的 OAuth App Client ID。

Native 插件不支持独立 APK 导出。功能可用性取决于框架、目标进程、设备权限和执行环境。
