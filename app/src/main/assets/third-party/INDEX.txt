# 第三方组件与许可

Easylsp 原创实现与原创文档采用 **GPL-3.0-or-later**。第三方组件按各自许可分发，保留原始版权和许可声明。

## 应用依赖

| 组件 | 锁定版本 | 来源 | 许可及说明 |
| --- | --- | --- | --- |
| Android apksig | 8.9.0 | https://android.googlesource.com/platform/tools/apksig/ | Apache-2.0；用于手机端独立 APK 签名 |
| AndroidX Activity / Lifecycle | 1.10.1 / 2.8.7 | AndroidX 官方 Maven / https://android.googlesource.com/platform/frameworks/support/ | Apache-2.0 |
| Compose BOM / Material 3 / UI | 2025.02.00 | 同上；BOM 管理组件版本 | Apache-2.0 |
| AndroidX Navigation Compose | 2.9.5 | AndroidX 官方 Google Maven | Apache-2.0；稳定 ID 路由和返回栈 |
| Material Design outlined icons | 24px SVG 路径，2026-11-04 获取 | https://github.com/google/material-design-icons/tree/master/src | Apache-2.0；仅将使用的图标路径转换为 Compose ImageVector |
| Sora Editor | io.github.Rosemoe.sora-editor:editor:0.23.6 | Maven Central / https://github.com/Rosemoe/sora-editor/tree/0.23.6 | LGPL-2.1-or-later；未修改上游源码，AndroidView 集成；自有词法高亮与片段补全 |
| Markwon core / ext-tables | 4.6.2 | Maven Central / https://github.com/noties/Markwon/tree/v4.6.2 | Apache-2.0；含 commonmark-java 0.13.0（BSD-2-Clause），Markdown 与表格渲染 |
| Termux terminal-emulator / terminal-view / JNI PTY | v0.118.3 | https://github.com/termux/termux-app/tree/v0.118.3 | GPL-3.0；保留原始头部与 Apache 来源说明。源码位于 terminal-component；以公开 ParcelFileDescriptor API 替换 FileDescriptor 反射，构建 arm64 libtermux.so |
| Room / SQLite | 2.7.2 | 同上 | Apache-2.0 |
| DataStore | 1.1.3 | 同上 | Apache-2.0 |
| Kotlin / Coroutines | 2.1.0 / 1.9.0 | https://github.com/JetBrains/kotlin / https://github.com/Kotlin/kotlinx.coroutines | Apache-2.0 |
| BeanShell | io.github.beanshell:bsh:2.1.1 | https://github.com/beanshell/beanshell/tree/2.1.1 | Apache-2.0，未修改上游源码 |
| Xposed API | 82，仅 compileOnly | https://github.com/rovo89/XposedBridge / https://api.xposed.info/ | Apache-2.0；框架由设备提供，不打包 API stub |
| libxposed API | 101.0.1，仅 compileOnly | https://github.com/libxposed/api/tree/101.0.1 | Apache-2.0；使用 101/102 共用公开接口，框架提供，不打包 API stub |
| ShadowHook | com.bytedance.android:shadowhook:2.0.0 | https://github.com/bytedance/android-inline-hook/releases/tag/v2.0.0 | MIT；未修改上游，arm64 早期 native 观测，原文随 APK 发布 |
| Android NDK target SDK | r28c / 28.2.13676358，API 34 | https://developer.android.com/ndk/downloads/revision_history | P0 包含 Android headers、AArch64 CRT/system stubs、compiler-rt builtins；保留 NDK NOTICE 与 NOTICE.toolchain 于生成的 `assets/native-sdk/android34-r28c.tar` 的 `licenses/`，含 BSD、Apache-2.0 WITH LLVM-exception 等上游条款；不打包主机编译器 |
| libxposed Service / Interface | 101.0.0 | https://github.com/libxposed/service/tree/101.0.0 | Apache-2.0；管理端通过官方服务读取激活状态与框架版本 |
| OkHttp / Okio | 4.12.0 / 3.6.0 | https://github.com/square/okhttp/tree/parent-4.12.0 / https://github.com/square/okio/tree/parent-3.6.0 | Apache-2.0；原文随 APK 发布，未修改 |
| DexKit | org.luckypray:dexkit:2.0.7 | https://github.com/LuckyPray/DexKit/tree/2.0.7 | 发布 POM 同时声明 LGPL-3.0 和 Apache-2.0；tag 根 LICENSE 为 Apache-2.0，部分源文件仍有 LGPL-3.0-or-later 头。保留两份条款与此说明，不按 main 分支许可证简化版本事实 |
| JADX core / input-api / dex-input | 1.5.1 | https://github.com/skylot/jadx/tree/v1.5.1 | Apache-2.0；保留上游 NOTICE；用于独立分析进程与依赖探针 |
| Smali / baksmali / dexlib2 | 3.0.8，分析直接使用并由 JADX 引入 | https://github.com/google/smali | BSD 条款及所含其他声明，原文见 APK assets/third-party/smali-baksmali/LICENSE |
| Guava | 33.3.1-jre，JADX 间接引入 | https://github.com/google/guava/tree/v33.3.1 | Apache-2.0 |
| Gson | 2.11.0 | https://github.com/google/gson/tree/gson-parent-2.11.0 | Apache-2.0 |
| SLF4J | 2.0.16 | https://github.com/qos-ch/slf4j/tree/v_2.0.16 | MIT |
| FlatBuffers Java | 23.5.26，DexKit 间接引入 | https://github.com/google/flatbuffers/tree/v23.5.26 | Apache-2.0 |
| libcxx Prefab | dev.rikka.ndk.thirdparty:cxx:1.2.0 | https://github.com/RikkaW/libcxx-prefab / https://github.com/topjohnwu/libcxx | Apache-2.0 WITH LLVM-exception 及上游所含许可，未修改 |

完整条款存于 `app/src/main/assets/third-party/`，随管理端 APK 发布；设置页可打开。JADX 的 NOTICE 含其汇总的第三方声明，实际启用组件为 Gradle 解析的 core 与 dex-input。Sora 仅使用公开 AAR；对应源码由上述 tag 和 Maven Central sources JAR 提供，可用仓库 Gradle 配置替换重建。新增组件的许可原文随 APK 发布。

## 构建与测试依赖

Gradle 8.11.1、AGP 8.9.1、Kotlin 插件 2.1.0（Apache-2.0）；JUnit 4.13.2（EPL-1.0）、Hamcrest（BSD）、AndroidX Test Runner 1.6.2 与 AndroidX Test JUnit 1.2.1（Apache-2.0）。MockWebServer 4.12.0（Apache-2.0）和 org.json 20240303（公共领域）用于 JVM 与 Android 本地可控服务协议测试。测试依赖不进入主 APK。Gradle Wrapper 来自官方 Gradle wrapper 任务。

除明确列出的 Termux 上游源码外，其余依赖通过公开 Maven 仓库取得，未使用反编译代码或参考 APK 的原生库。依赖版本、对应源码入口与再构建方法一同保留。

Frida 后端不随包分发 frida-inject 或 Java bridge；使用用户设备上的可执行文件。独立模板附带自身 GPL、BeanShell 与 Apache 许可，模板没有绑定管理端服务。

Ubuntu 环境按需下载 Canonical 官方 Ubuntu Base 24.04.3 arm64 rootfs，未随 APK 分发。发行包包含多种开源软件，许可与版权保留在安装后的 `/usr/share/doc/`；来源地址记录于安装元信息。

Native 构建通过 Ubuntu 24.04 软件仓库安装 clang-18 / lld-18 和 Python 3；其版本原文进入 execution 日志，许可保留在手机 Ubuntu 的 `/usr/share/doc/`。Clang/LLD 来自 LLVM（Apache-2.0 WITH LLVM-exception 及对应上游声明），Python 采用 PSF 及所含声明。主机验证组合为 Ubuntu Clang/LLD 18.1.3 与 NDK r28c 的目标文件。

Harness 在上述环境使用官方 Node.js 24.11.0 Linux arm64 发行包（MIT 及其 LICENSE 中列明的第三方条款，https://nodejs.org/dist/v24.11.0/）与 npm 包 `@deepseek-ai/dsh@0.2.2-alpha.3`（MIT，https://github.com/deepseek-ai/deepseek-harness）。依赖精确记录于 `harness/package-lock.json`，许可保留在各安装包中；Node 和 npm 依赖不打入 APK，部署使用固定 lockfile；宿主直接依赖 `ws@8.22.1`（MIT，https://github.com/websockets/ws）。原生挂载命名空间启动器为本仓库原创实现。

- SnakeYAML 2.3 — Apache License 2.0. Parses standard Skill YAML frontmatter using SafeConstructor. https://bitbucket.org/snakeyaml/snakeyaml
- MCP TypeScript client 2.2.0 — MIT License, Anthropic. The Harness dependency supplies HTTP/SSE/stdio transports and OAuth/PKCE. https://github.com/modelcontextprotocol/typescript-sdk
