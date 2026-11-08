# Easylsp 插件 API v1 / v2

## 文件格式

导入/导出是 ZIP，根目录包含 UTF-8 info.prop 与 main.java，可附带文本资源和 lib/ 依赖；API v2 增加 plugin.json 与组件源码。文本每个文件至多 1 MiB。导入时分配新的内部 UUID，默认停用。

```properties
id=example-id
name=示例
version=1.0
author=Easylsp contributors
description=原创演示插件
apiVersion=1
entry=main.java
processName=
```

`processName` 为空表示项目主进程；子进程必须填写完整包名与冒号后缀。导出保留此字段，导入时核对所属项目。

脚本与各次 revision 保存在管理端私有文件；Room 保存元信息、唯一权威的 `desiredEnabled` 与当前 revision。文件描述符交付指定 revision，不依赖共享外部存储或 `.enabled` 文件。

## 全局对象

| 对象 | 内容 |
| --- | --- |
| `context` | 目标 Application |
| `classLoader` | 目标 ClassLoader |
| `hook` | 当前插件实例的 Hook 登记器 |
| `plugin` | `id`、`revision`、`instanceId` 与 `log(Object)` |

脚本由 BeanShell 2.1.1 解释执行。`HookCallback` 与 `HookParam` 自动导入。回调可以使用 BeanShell 的接口代理；不支持在 Android 上依赖 JVM 字节码生成的任意 Java 类声明。

```java
void onLoad() {
    hook.after("io.github.easylsp.demo.Calculator", "add",
        new String[] { "int", "int" }, new HookCallback() {
            public void call(HookParam p) {
                plugin.log("原结果=" + p.getResult());
                p.setResult(Integer.valueOf(42));
            }
        });
}
void onUnload() { plugin.log("正在卸载"); }
```

## Hook 语义

- `before(owner, name, parameterTypeNames, callback)`：可修改 `p.args`；`p.skipWith(value)` 明确跳过原方法。
- `after(...)`：读取原结果/异常；`p.setResult(value)` 清除异常并替换结果，`p.setThrowable(error)` 设置异常。
- `replace(...)`：不执行原方法，回调必须显式设置结果或异常。返回 void 的方法使用 `p.setResult(null)`。
- 返回 `HookHandle`，调用 `unhook()` 或 `close()` 释放。卸载实例自动释放所有该 API 登记的句柄。
- `p.member`、`p.thisObject`、`p.args` 为当前调用；`getResult()` / `getThrowable()` 为结果或异常。
- 通过完整参数类型选择**声明在指定类中的方法**，不随意选择首个同名重载。继承方法请指定声明类。构造器名为 `<init>`；支持基本类型、类名、数组，静态方法使用相同接口。
- BeanShell 数值写入 Java 数组/结果时请用正确包装类型，如 `Integer.valueOf(4)`；不要依赖隐式窄化转换。
- 脚本回调异常记录到该插件实例并交给框架处理；不伪造正常返回。原目标异常可由 after 显式处理。

## 生命周期与界面动作

- **保存**：新文件 revision，不改变已运行实例；有未保存缓冲区时先保存。
- **启用**：持久化期望启用，并发布下一次启动的完整 Java 快照；在线时可请求加载。发布版本固定，保存新 revision 后需显式发布才能用于下次冷启动。
- **加载**：执行脚本顶层，再调用可选 `onLoad()`；同一 revision 已运行时不重复安装 Hook。不同 revision 必须显式重载。
- **卸载**：调用可选 `onUnload()`，逐个清理全部句柄；脚本清理或底层解除失败时，实例保留为错误状态，可再次清理。重试会再次调用失败的清理方法，已成功返回的方法不重复执行。不改变期望启用。
- **停用**：持久化关闭并请求卸载；离线时保留配置待下次同步。
- **重载**：先做不执行脚本的语法解析；语法错误保留旧实例。解析通过才卸载旧实例并加载新 revision，不回滚任意 `onLoad` 副作用。
- **删除**：在线时先请求卸载，再移除权威记录与文件。

加载/卸载操作在目标的专用工作线程串行执行，方法回调仍在原目标调用线程执行；脚本自身共享变量的并发由脚本负责。卸载不回滚直接修改字段、外部线程或其他绕过本 API 的副作用。脚本应在清理成功后移除自身回调与资源；清理方法应允许失败后重试。异步清理回调保留原实例的命名空间，但卸载返回不代表异步任务已完成。

## 会话与日志

每次目标连接获得新 sessionId；插件运行实例有新 instanceId，事件携带 revision。旧会话不能更新新连接。不同进程各有独立会话，插件按 processScope（exact/main/children/all）匹配指定进程、主进程、子进程或包内全部进程；每个进程仍有独立实例。在线状态来自 Binder 会话死亡通知，运行态来自目标确认；命令超时显示超时，不等于目标已撤销操作，可用状态查询或日志确认。

桥接断连不卸载业务插件；相同 revision 重连报告原 instanceId。已发布快照包含脚本、库、作用域、依赖顺序和 generation，冷启动无需管理器进程。空快照撤销后续启动；运行中的旧 revision 只在显式 reload、停用同步或目标退出时改变。

日志文件为 JSONL，包含时间、包名、进程、pid、sessionId、pluginId、revision、instanceId、级别与消息。界面显示该项目最近 500 条，可搜索文本、插件 ID、进程和级别；导出和清空作用于全部日志，按钮明确标注。单条消息最多 16000 个字符，超长记录有截断标记。


## 0.6 API v1 扩展：依赖与反射

插件 ZIP 可在 `lib/` 根目录保存 `.dex` 或含 classes.dex 的 `.apk`。每个依赖最多 16 MiB；加载时使用插件实例自己的 InMemoryDexClassLoader，父加载器为目标应用。依赖随已保存 revision 固定；保存后必须显式重载。卸载会清理解释器、Hook 句柄及平台保留的加载器引用，Java 对象是否最终回收仍由 JVM 引用关系决定。JAR/AAR 需在外部转换，当前不在 Android 端自动转换。

`reflect` 使用目标/依赖加载器查找类：

```java
Class type = reflect.type("your.package.Target");
java.lang.reflect.Field field = reflect.field("your.package.Target", "value");
Object value = reflect.get(instance, "value");
reflect.set(instance, "value", Integer.valueOf(7));
Object result = reflect.call(instance, "method", new Class[] { String.class }, new Object[] { "text" });
```

field/get/set 会沿父类查找字段；method/call 使用给定类声明的方法和完整参数类型。静态字段可用 `reflect.field(...).get(null)`，静态方法可用 `reflect.method(...).invoke(null, args)`。DEX 方法签名与引用查询仍由分析工具提供，模型应先获取真实签名再注册 Hook，不猜测类名和重载。

## 一次性调试与 UI 模板

`debug_target_once` 在 `Easylsp-debug-once` 线程执行 BeanShell，默认 3000 ms、最多 5000 ms。提供 `context/classLoader/reflect/hook`；返回值可用 `reflect.inspect(object)` 查看最多 64 个字段，不递归调用任意对象的 `toString()`。脚本结束或超时后清理托管 Hook；忽略中断的脚本不能被安全强杀。

对象查看示例（先换成已确认的业务类及方法）：

```java
Object value = reflect.call(context, "getApplicationInfo", new Class[0], new Object[0]);
reflect.inspect(value);
```

Activity/View/adapter 使用 UI 线程工具模板，`SESSION` 可在项目只有一个在线目标时省略：

```json
{"sessionId":"SESSION","operation":"viewTree","arguments":{"query":"RecyclerView","offset":0,"limit":30}}
{"sessionId":"SESSION","operation":"viewNode","arguments":{"snapshotId":"上一步返回值","path":"上一步节点 path","relation":"ancestors"}}
{"sessionId":"SESSION","operation":"viewNode","arguments":{"snapshotId":"当前快照","path":"当前节点 path","relation":"children"}}
{"sessionId":"SESSION","operation":"viewClick","arguments":{"snapshotId":"当前快照","path":"按钮 path"}}
{"sessionId":"SESSION","operation":"viewScroll","arguments":{"snapshotId":"当前快照","path":"列表 path","forward":true}}
```

View 行含 Activity、坐标和属性；RecyclerView 行另含 adapter 类名与 itemCount。快照变化后重新读取 path，操作返回即时快照，异步布局需再读 viewTree。`end_target_debug` 释放帧采样、View 创建堆栈 Hook、浮层和连接，业务插件继续运行。

`java_hook_metrics` 可显式开启命中/异常计数，默认关闭；Native 状态复用安装及首次命中事件。`record_plugin_validation` 保存功能 PASS/FAIL/NOT_TESTED 和观察证据；初始化、安装或命中均不自动等同功能成功。

## 源码语法预检

调用 `validate_plugin(pluginId: "...")` 检查已保存的 BeanShell 插件，或用 `validate_plugin(source: "...")` 检查未保存草稿。两者只能指定一个。返回 `valid`；语法错误包含行号、列号及附近源码。此操作只解析，不保存、不发布、不调用 onLoad，也不连接目标进程；语法通过不代表运行时类、Hook 或业务功能正确。Native 插件使用现有构建工具检查。

## 调试窗口、结果与错误

viewTree 枚举当前进程的已附着窗口，包括 Activity、Dialog 和 PopupWindow。path 是当前 snapshotId 内的窗口路径；弹窗关闭后该路径失效。Compose 语义和 WebView DOM 仍不在此范围内。

viewClick/viewScroll 默认返回 performed、node 和目标身份，不附加整个子树。需要快照时传 includeSnapshot=true、snapshotLimit=1..100（默认 10），超过上限返回 snapshotTruncated=true。viewNode 默认返回单节点；relation=children 默认返回有界直接子节点。异步布局完成后可重新读取 viewTree。

debug_target_once 的 result 直接保留对象、数组、数字、布尔和 null；字符串不会猜测为 JSON。reflect.inspect 的字段作为对象返回。复杂对象图限制深度、元素及字符串长度，循环/截断显式标记，未知对象只返回类型信息。

目标错误跨 Binder/MCP 保留 structuredContent.error.code 和 message：STALE_SNAPSHOT 表示快照已替换，STALE_NODE 表示窗口关闭或节点变化，SCRIPT_TIMEOUT 表示一次性脚本超时。客户端无需解析 Java 异常包装文本。超时不能安全强杀忽略中断的脚本。

## API v2：多文件程序、组件与跨插件服务

同一功能的增强和修复应修改已有插件。先用 list_plugins/read_plugin_files 读取结构，用 write_plugin_files 一次保存多个文件并生成一个 revision；只有需要独立安装的功能才创建插件。编辑器支持“添加内部组件”和整包“检查”。

普通 ZIP 使用 apiVersion=2；main.java 保留为入口，plugin.json 声明程序结构，src/*.bsh 为组件，其他文本可通过 plugin.readText(path) 读取。旧 API v1 单文件插件继续加载。在线、冷启动和独立 APK 使用相同的固定源码包；保存仍不会自动替换运行实例。

示例 plugin.json：

~~~json
{
  "schema": 1,
  "components": [
    {"id": "config", "path": "src/config.bsh"},
    {"id": "panel", "path": "src/panel.bsh", "requires": ["config"]}
  ],
  "services": {
    "provides": {"example.settings": 1},
    "requires": {"example.models": 1}
  }
}
~~~

components 和 services 都可省略。组件按 requires 排序，缺失、重复与循环直接报错。每个组件有自己的命名空间，继承 context/classLoader/reflect/plugin，拥有独立 hook 登记器和 component.resources。组件源码末尾写 return this;，以脚本对象导出方法。组件通过 component.require("config") 获取声明过的内部依赖；入口通过 plugin.component("panel") 取得组件。它们属于同一插件和 revision，没有独立安装记录。

所有组件先求值，再执行入口顶层；随后按依赖顺序调用组件 onLoad，最后调用入口 onLoad。卸载先调用入口 onUnload，再逆序调用组件 onUnload。组件顶层用于定义方法，副作用放入生命周期方法。首版按整个插件重载。语法预检涵盖全部声明脚本，错误包含原始文件名；运行时类型、方法和业务结果仍须实际验证。

### 资源归属

plugin.resources 管理入口资源，component.resources 管理组件资源：

- add(AutoCloseable)：登记关闭操作。
- onClose(Runnable)：登记同步清理，例如注销监听器或关闭线程池。
- onUiClose(Runnable)：在 Android 主线程执行并等待清理完成，单次等待最多 5 秒；超时保留待清理状态，再次清理等待同一个任务，不重复排队。

清理按登记逆序执行，成功项不重复执行，失败项保留并报告 ERROR。Hook 仍由所属 hook 登记器自动释放。只有宿主托管的清理可确认完成；自行 post 的任务或自行启动的线程仍由脚本负责。onUiClose 中不要等待加载/卸载线程，服务和业务回调运行在调用方线程。

### 跨插件服务

服务作用域是同一个目标进程的 Easylsp 引擎。提供者和消费者必须配置相同进程范围；all/children 下每个进程各有自己的服务实例。跨进程、跨应用及不同独立 APK 引擎之间不共享服务。

服务名使用稳定的命名空间名称，例如 example.models；版本为正整数且精确匹配。安装和发行依赖继续使用市场 dependencies；服务依赖描述运行时接口，不会自动下载或启用提供者。先启用提供者，再启用消费者，冷启动与在线同步自动按服务依赖排序；手动加载同样须先加载提供者。

提供者须在 onLoad 返回前登记所有声明服务，全部加载成功后才可供消费者调用。不要将接口匿名对象直接嵌套为 provide 的第二个参数，当前 BeanShell 解析器对这种写法有限制；先赋值：

~~~java
void onLoad() {
    PluginService handler = new PluginService() {
        public String call(String operation, String request) {
            return request;
        }
    };
    plugin.services.provide("example.echo", handler);
}
~~~

消费者声明 services.requires 后调用：

~~~java
void onLoad() {
    PluginService echo = plugin.services.require("example.echo");
    plugin.log(echo.call("echo", "{}"));
}
~~~

请求和响应均为 JSON 对象或数组的文本，避免跨 ClassLoader 传递插件私有对象。调用同步发生在调用方线程，耗时操作由插件明确放到工作线程。回调异常直接交给调用方，不自动重试。

仍有运行消费者时，提供者卸载/重载被拒绝；先卸载消费者，再操作提供者，最后重新加载消费者。服务调用尚未结束时也拒绝卸载相关实例。提供者或消费者卸载后旧句柄失效，重新加载须重新获取。重复提供者、缺失接口、版本不符和依赖环明确失败。独立 APK 可包含内部组件和自身服务，但不能导出依赖外部服务的消费者。

示例目录：component-demo、service-provider、service-consumer。validate_plugin 支持 pluginId、files、source 三选一；write_plugin_files 要求 baseRevision，并可同时提交 files/removePaths。已保存 revision、冷启动发布 revision 与运行 revision 仍是不同状态。

管理器与目标宿主通讯协议升级为 v4，升级管理器后须重启目标应用。卸载开始时服务句柄即失效，onUnload 与资源清理不能继续调用服务。部分初始化失败也会逆序执行已创建组件的 onUnload 和资源清理，因此清理方法应对应已经创建的资源。
