---
name: manage-mcp
description: 新增、修改、启停或排查 MCP 服务连接，包括远程 HTTP/SSE 与 Ubuntu 中的 stdio 服务；用于服务配置和连接问题。
---

# 配置 MCP

使用应用配置接口管理服务，让 UI 和模型使用同一份配置。远程服务需要真实 URL 和 transport；stdio 服务使用 Ubuntu 内的 command、args、cwd 与 envRefs。不要将 Android shell 路径和 Ubuntu 路径混为一谈。

凭据通过 credentialRef／envRefs 提供，读取配置只返回已设置状态。保留用户已有连接参数，修改任务涉及的字段即可。

test_mcp_server 核对连接和服务声明能力；refresh_mcp_tools 刷新真实目录。连接成功不代表业务工具可用或远程操作已完成。依据实际错误处理，不增加无依据的自动重试。

保存服务不会自动把全部工具塞给模型；需要执行业务时查询相关工具并使用。模型不必先使用 use-mcp Skill，也不必再次确认用户已经授权的配置变更。
