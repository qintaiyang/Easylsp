# Native 插件 SDK v1

Android 12+ / arm64-v8a / C17。读取 `native-header` 获取准确函数表和类型；读取 `native-symbol-example`、`native-vaddr-example`、`native-lifecycle-example`、`native-jni-example` 获取示例。插件只能导出 `easylsp_plugin_init_v1`，不链接另一份 ShadowHook。

## 创建、构建和发布

1. `create_plugin(runtime="NATIVE", name, source)` 创建停用的 `src/main.c`。`write_plugin_file` 保存 `src/` 或 `include/` 下附属文件，每次修改增加 revision；保存不会更新已发布版本。
2. 手机 Ubuntu 就绪后执行一次 `prepare_native_build_tools`，用返回的 executionId 调用 `read_execution`。这与只准备分析 LLVM 的 `prepare_native_tools` 不同。编译器为 Ubuntu arm64 Clang/LLD 18，Android 目标文件为 NDK r28c/API 31。
3. `build_native_plugin(pluginId, sourceRevision, sampleRevision)` 构建固定保存版本。所有 `src/**/*.c`、`src/**/*.cpp` 分别按 C17/C++17 编译后一起链接，受控 include 路径为 `include/`、`src/`，无插件安装脚本。使用该任务的 `read_execution` 读取编译错误；不要为轮询重复构建。
4. 编译器与产物检查通过后，`enable_native_plugin(pluginId, buildId, policy)` 发布指定成功构建。policy 必须明确选 `NATIVE_ONLY` 或 `NATIVE_AND_JAVA`；前者跳过模块 Java Hook、BeanShell 和 Inspector，后者继续 Java 插件。策略作用于项目。
5. `start_target(restart=true)` 启动新进程。`get_native_plugin_status` 查询实际 PID/startTicks/instanceId、构建、generation 和 Hook 证据；`read_plugin_logs` 可按 pluginId/instanceId 查询，历史 Java 会话不会屏蔽新 Native 实例。
6. `disable_native_plugin` 从下一次启动快照移除。当前进程内代码保留到进程退出；需要立即停止其行为时显式重启。不要调用 Java load/reload/unload 工具处理 Native。

构建、发布不需要目标在线，不依赖 Binder。`NATIVE_ONLY` 没有 Java session 是正常现象。源码已保存、构建成功、发布成功、插件初始化、Hook 安装、Hook 命中、目标问题修复是不同证据。没有运行事件时只能说“尚无执行证据”。日志可能被系统覆盖，完整启动调查应先开启诊断。

## ABI 与阶段

入口接收 `sh_host_v1` 与 `sh_plugin_context_v1`，先检查 ABI 和 struct_size。上下文包含固定源码、构建、发布及进程身份。符号/地址/订阅注册只允许在入口内同步执行；JNI 请求允许在已附着 VM 线程的 JNI_OnLoad 包装器中登记；替换函数、原函数指针存储和订阅回调需具有静态/进程生命周期。

- `hook_symbol` 接受库 basename 或精确 linker 路径；`hook_symbol_selected` 还可指定已有 ELF Build ID。返回 SH_OK 表示已有库已安装，SH_WAITING 表示等待未来加载；最终以对应 Hook 事件为准。
- `SH_REQUIRE_BEFORE_CONSTRUCTORS` 遇到已加载库返回 SH_MISSED_STAGE，不会补造构造前时机。未来模块在构造前同步安装。
- `hook_elf_vaddr` 必须提供分析样本的 GNU Build ID 和 ELF_VADDR。运行时校对 Build ID，使用实际 load bias + vaddr，要求地址位于可执行 PT_LOAD。文件偏移不能直接当 vaddr。`native-vaddr-example` 的占位值必须替换后才能使用。
- 模块选择器可用 name、精确 path 和可选 Build ID；同名已加载库不唯一返回 SH_AMBIGUOUS，需要更精确路径。APK 内路径使用 linker 看到的 `base.apk!/lib/arm64-v8a/...`。
- `subscribe_module` 按位选择 existing / before constructors / after constructors / before unload。回调中的 load_id 标识本次加载，结构和字符串只在回调期间有效。existing 不能解释为构造前事件。
- 卸载前通知订阅并解除该次 Hook；原函数指针清空，下一次加载重新定位和安装。插件 SO 始终保留，不对可能执行中的代码 dlclose。
- 默认 EXCLUSIVE（ShadowHook UNIQUE）模式同地址只允许一个所有者，冲突事件带请求插件与已有 owner。错误 Build ID、错过阶段、符号缺失、地址冲突均不能解释为生效。
- 初始化返回非零会撤销此实例已登记 Hook 和订阅，保留代码映射。

定型 C 包装函数表达 before/after/replace，按真实声明处理参数、原函数与返回值，保留函数契约需要的 errno。变参、结构体返回或 JNI 签名不确定时先分析。JNI_OnLoad 示例使用确认的 `jint(JavaVM *, void *)`，不依赖 Java Context。纯替换函数可以不调用原函数，但仍需真实 ABI。

linker 回调中不要调用 Java、网络/磁盘 I/O、dlopen、注册新 Hook 或订阅。业务初始化放在显式入口，不用插件构造函数安装 Hook。壳私有映射、匿名/解密代码不保证产生标准 linker 模块事件。

## 包与限制

Native ZIP：`info.prop` 指定 `formatVersion=2,runtime=native,nativeApiVersion=1,entry=src/main.c,abi=arm64-v8a,minApi=31`，包括 src/ 和可选 include/、README.md、LICENSE、config.schema.json；可附 `native/arm64-v8a/plugin.so`。导入默认停用；附带 SO 记录为导入产物，不据此证明附带源码就是二进制来源。导出源码包后可在手机重建。Java ZIP v1 保持兼容。Native 独立 APK 与额外运行时 SO 暂不支持。C++17、静态 libc++/libc++abi/libunwind 已支持；STL/异常不得跨插件 ABI 边界。历史 v2 的 minApi=34 包仍接收，但只允许在 API34+ 设备安装预编译产物；新源码包为 minApi=31。

产物接收检查 ELF64/AArch64/ET_DYN、与声明一致的 Android API note（新构建为31，历史34包保留接收）、唯一导出入口、16KB PT_LOAD 对齐和 libc/libdl/liblog/libm 依赖。16KB 对齐不代表已经在 16KB 设备上验收。进程上限 16 插件，每插件 32 Hook 和 16 订阅；事件缓冲 128 条，首次命中写日志，后续命中在进程内计数。

诊断隔离：ENTRY_ONLY 不加载引擎/用户插件；NATIVE_OBSERVE 只运行内置观测；JAVA_NO_PLUGINS 保留 Java 初始化并抑制用户插件；NORMAL/EXTERNAL_ONLY 按发布策略执行。普通诊断窗口中的用户 Native 事件保存在 native-plugin-events.jsonl。

## 可选 ABI 尾部扩展（0.9.1 / ShadowHook 2.0.1）

旧函数表前缀和入口不变，旧已编译插件无需重编译。使用新增字段前按 `offsetof + sizeof(field)` 检查 `host->struct_size`；不能仅检查 ABI 版本。

- `hook_symbol_options` / `hook_elf_vaddr_options` 接受 `sh_hook_options_v1`：EXCLUSIVE 保持原语义，CHAIN 共享同一真实函数，先发布者在外层，`original` 指向链中下一项。每个包装器自行调用 typed next；递归、errno 和真实 ABI 由其函数契约决定。持久化项目发布顺序决定加载次序；切换次序后重启目标。混合独占/链会报告冲突，不能伪称全部启用成功。
- `hook_jni_method` 匹配精确 `jclass` 或精确 ClassLoader + 点分类名，并同时匹配方法名、完整签名；loader 为 NULL 只表示 boot loader。返回 WAITING，直到下一次真实 RegisterNatives。只替换该类的 JNI 方法表项，不 inline-hook 共享 C 地址，避免误伤其他 loader；不修改重载、JNI 返回值或原 pending exception。UnregisterNatives 后回到 WAITING，重注册重新绑定；已发生而未观测的注册不能伪造绑定。并发业务调用受 JVM 方法注册语义约束，插件不得在业务线程仍调用旧 next 时卸载其目标库。
- `intercept_elf_vaddr` 只支持 ARM64 可执行 ELF 指令地址，要求精确 Build ID；`sh_arm64_registers_v1` 提供 x0–x30，可选 32 个 128 位 SIMD 槽。SP/PC/PSTATE 只读；未选择 SIMD_READ_WRITE 时不可用其内容。回调必须短且不执行 I/O/JNI/递归引擎注册。它不改变控制流、不进行任意指令翻译。
- 能力声明分别为 `native.chain`、`native.jni-register`、`native.intercept`；示例在 `examples/native-plugins/chain-a`、`chain-b`、`jni-register`、`instruction-intercept`。运行支持取决于框架提供者、设备和目标进程。

Core 事件包含 backend/coreVersion/releaseId、UID/PID/startTicks、instanceId/buildId/generation、sequence/dropped。Zygisk 使用 root 只读进程事件端点，Java/libxposed 保持原有读取方式；没有事件不能推断插件生效。

## M7 尾部扩展

SDK `p0-v4-api31-m7` 保留 v1 前缀，追加 `hook_plt`、`register_tasks`、`remove_hook`、`unsubscribe_module`。调用前按尾部字段检查 struct_size。PLT selector 指定调用方 ELF 的唯一 JUMP_SLOT，EXCLUSIVE；保留 original，其他调用方不变。支持已有与未来装载，解除时 CAS 检查当前值，冲突不覆盖。运行中批次只允许普通线程同步登记 Native Hook/模块订阅，禁止 JNI、I/O、dlopen、等待与递归；回调失败不会回滚已成功请求。解除不卸载插件、保留 original 存储，实际目标卸载后不得继续调用旧 original；JNI 解除返回 UNSUPPORTED。模块/intercept 回调不得动态登记或解除。

无 ART 的通用 Native 入口通过独立 launcher 或 Next companion 的 FD 加载 Core，不附加 JNI。不宣称任意 fork 后状态可继续使用；采用显式 exec。完整配置、生命周期、示例见仓库 `docs/native-m7-guide.md`。
