已核对加载关系，下面保留树形代码、单元格换行与较长表格。

```text
AW.attachBaseContext()
  └─ H.load(ApplicationInfo)
      ├─ System.loadLibrary("DexHelper") 〔ARM 路径〕
      └─ 失败时调用 H.loadLib(...)
```

| 途径 | 本项目的证据 | 能否确认用于检测 |
|---|---|---|
| 扫描进程内存映射 | 读取 `/proc/self/maps`；目标中存在 APK 映射 | **尚不能**，目前分析到的循环是在定位自身 |
| 查找类／ClassLoader | 查询直接字符串返回 0 | 未定位具体实现；不排除后阶段代码 |
| 检查调试状态 | 读取 `TracerPid`、`PPid` | 属于线索，不能单独确认 |
| 校验函数入口 | 当前没有定位对应代码与执行证据 | 仍是候选 |

| 项目 | 确认结果 |
|---|---|
| 插件 | 保存及发布版本均为 **r9** |
| 另一插件 | 构建成功；**尚未发布** |
| 当前发布版本 | 仍为 **r2** |
| 执行证据 | 历史有安装事件<br>当前存活尚未查询 |

```text
┌─────────────────────────────┐
│ 来源 → 版本 → 构建 → 运行实例 │
└─────────────────────────────┘
long_line_abcdefghijklmnopqrstuvwxyz_abcdefghijklmnopqrstuvwxyz_abcdefghijklmnopqrstuvwxyz_abcdefghijklmnopqrstuvwxyz
```
