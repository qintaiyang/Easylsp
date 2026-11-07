# Easylsp 插件 API v1

## 文件格式

导入/导出是 ZIP，根目录只包含 UTF-8 `info.prop` 与 `main.java`，每个文件至多 1 MiB。导入时分配新的内部 UUID，默认停用，不信任外部 id 或目录路径。

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
- **卸载**：调用可选 `onUnload()`，逐个清理全部句柄；底层解除失败的句柄与实例保留为错误状态，可再次清理。不改变期望启用。
- **停用**：持久化关闭并请求卸载；离线时保留配置待下次同步。
- **重载**：先做不执行脚本的语法解析；语法错误保留旧实例。解析通过才卸载旧实例并加载新 revision，不回滚任意 `onLoad` 副作用。
- **删除**：在线时先请求卸载，再移除权威记录与文件。

加载/卸载操作在目标的专用工作线程串行执行，方法回调仍在原目标调用线程执行；脚本自身共享变量的并发由脚本负责。卸载不回滚直接修改字段、外部线程或其他绕过本 API 的副作用。

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
