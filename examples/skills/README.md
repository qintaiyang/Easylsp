# Easylsp Skill 示例

`presets/` 包含 11 个预设指导包；每个目录含标准 `SKILL.md` 和 Easylsp 专属 `easylsp.json`。App 已接入工具发现与直接选择、配置、会话、记忆、phone_use 和 manifest 选择机制；实际可用状态取决于会话作用域、用户启停和设备条件。

Skill 用于提供经验与方法，模型可以组合、调整或不使用。常用工具直接可用，其他工具可自主搜索加载，工具调用不以激活某个 Skill 为前提。目标是避免无关上下文干扰并充分发挥模型能力，不是压低 token。

## 预设与适用任务

| 预设 | 适用任务例子 | 建议工具组 | 通常无需使用的情况 |
| --- | --- | --- | --- |
| [explore-capabilities](presets/explore-capabilities/SKILL.md) | “这个任务能用哪些现有能力完成？” | 核心发现工具即可 | 已知工具可直接完成 |
| [phone-control](presets/phone-control/SKILL.md) | “打开设置，调整亮度” | phone | 解释什么是亮度 |
| [workspace-task](presets/workspace-task/SKILL.md) | “整理附件，生成并验证一个脚本” | workspace.files、workspace.execution | 只问一个代码概念 |
| [recall-memory](presets/recall-memory/SKILL.md) | 任务依赖以前的结论；保存或修订约定 | memory，可选 sessions | 当前材料已充分，不需要历史依据 |
| [manage-models](presets/manage-models/SKILL.md) | “同一供应商添加两个模型，分别设置参数” | config.models | 只比较模型知识，无配置需求 |
| [manage-mcp](presets/manage-mcp/SKILL.md) | “配置这个 MCP 并检查连接” | config.mcp、extensions | 使用现成服务即可 |
| [use-mcp](presets/use-mcp/SKILL.md) | 使用已配置服务的工具或资源完成任务 | extensions | 无外部服务需求的普通问答 |
| [author-skills](presets/author-skills/SKILL.md) | “把这个工作方法整理成 Skill” | config.skills | 一次普通任务没有制作包的需求 |
| [coordinate-agents](presets/coordinate-agents/SKILL.md) | 查历史、建分支、组织可并行的子任务 | sessions，可选 agents | 单步任务可直接完成 |
| [analyze-app](presets/analyze-app/SKILL.md) | 查询当前项目 APK 的方法与引用 | project.analysis | 仅操作手机界面 |
| [develop-hook](presets/develop-hook/SKILL.md) | 编写、运行并验证项目插件 | project.plugins，按需选运行时和诊断 | 不涉及插件的问答 |

这些场景帮助模型发现方法，不是关键词触发白名单，也不是强制的流程路由。后两项只适用于 PROJECT；其余可在 GLOBAL／PROJECT 中使用。

## 工具建议与完整能力

[tool-groups.json](tool-groups.json) 给出宿主侧的示例分组、默认工具和引导工具，供开发者核对，**不整份注入模型上下文**。它包含现有分析、检视、Native、脚本、环境和导出工具，避免新预设遗漏旧能力；运行时的实际注册表才是工具发现来源。

manifest 的 `toolGroups` 表示使用此 Skill 时便捷选择的建议组，`optionalToolGroups` 表示按任务额外建议。二者不限制模型使用其他工具；缺少某工具仍可读取指导。多个 Skill 可以共同持有工具选择，释放一个不会移除其他来源仍需要的能力。

直接调用路径：常用工具可立即用；专用工具 `search_tools` → `select_tools` → 调用，知道名称可跳过搜索。Skill 路径：`list_skills` → `activate_skill`，同时得到方法和建议工具；也可以仅 `read_skill` 后自行行动。MCP wrapper 不强制先使用 Skill 或选择直接工具。

## 打包与当前导入限制

每次只打包、导入一个 Skill 目录。当前导入器期望包中只有一个 `SKILL.md`，不要把整个 presets 目录作为一个 Skill 导入。例如在本目录执行：

```sh
cd presets
zip -r /tmp/easylsp-phone-control.zip phone-control
```

导入器使用 YAML 解析标准 Skill frontmatter，读取 easylsp.json 的建议／可选工具组和作用域。预设首次启动安装一次；激活／释放使用独立引用，工具是否实际可用由当前注册表、启停与会话模式决定。

已有 [inspect-workspace.sh](inspect-workspace.sh) 和 [echo.java](echo.java) 是旧执行型示例；新增预设不替换它们。现有工具插件示例另见 [tool-plugins/echo.java](../tool-plugins/echo.java)。

## 格式验证与贡献

manifest 格式见 [skill-capabilities.schema.json](../../app/src/main/assets/skill-capabilities.schema.json)。有 Python `jsonschema` 的环境中，可在仓库根目录执行：

```sh
python3 - <<'PY'
import json
from pathlib import Path
from jsonschema import Draft202012Validator
schema = json.loads(Path('app/src/main/assets/skill-capabilities.schema.json').read_text())
Draft202012Validator.check_schema(schema)
validator = Draft202012Validator(schema)
for manifest in sorted(Path('examples/skills/presets').glob('*/easylsp.json')):
    validator.validate(json.loads(manifest.read_text()))
    print(manifest.parent.name, 'OK')
PY
```

另核对 frontmatter 名称／描述、资源链接、建议组存在且支持该作用域、默认建议与可选建议不重复。格式正确不能证明模型会正确使用 Skill；运行时接入后验证真实任务效果、无 Skill 的直接工具使用、资料展开和长任务连续性。
