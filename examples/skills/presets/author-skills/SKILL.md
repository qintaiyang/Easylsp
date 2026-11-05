---
name: author-skills
description: 创建、编辑、安装、启停 Easylsp Skill 包，整理有复用价值的工作方法与按需参考资料。
---

# 编写和管理 Skill

先确定这个 Skill 会改变模型的哪些判断、面向什么实际任务。正文记录有用的方法和非显然约束，不重复系统规则、不要求每次走统一流程，也不把本包变成其他能力的门槛。

SKILL.md 使用 YAML frontmatter：name 为小写连字符名称，description 说明用途和适用场景。普通说明不需要 scripts／references；确有可执行复用逻辑或长资料时再添加，并在正文注明何时需要。

easylsp.json 声明 schemaVersion=1、scopes、建议 toolGroups 和 optionalToolGroups。工具名称和组以当前注册表为准；建议列表不限制模型调用其他工具，不允许包自行授予权限。

create_skill／update_skill 保存包文件并同步元数据。install_skill 导入一个包含 SKILL.md 的目录；用户要求安装并启用时按已有授权完成，启用不会自动执行脚本。修改正在使用的 Skill 时说明新 revision 下次使用生效。

检查 frontmatter、资源链接、工具建议和真实示例是否一致；只对新增或修改的脚本做相应执行验证。不要为了“标准结构”创建无用占位目录或重复文档。
