# 文档索引

| 文档 | 适合谁 | 内容 |
| --- | --- | --- |
| [protocol.md](protocol.md) | 想适配自己车盒的人 | BLE 帧格式、TLV、密钥派生、指令表、GATT UUID |
| [build.md](build.md) | 想从源码出包的人 | 工具链准备、签名、构建流程、目录约定、常见问题 |
| [verification.md](verification.md) | 想知道现在能用多少的人 | 已验证 / 待验证清单、调试命令、已知限制 |
| [dev-notes.md](dev-notes.md) | 想知道「为什么这么设计」的人 | 原始开发笔记完整归档：推理过程、协议摸索、UI 决策、踩坑记录 |

根目录的 [README.md](../README.md) 是项目门面：简介、安装、快速开始、范围与免责声明。

## 关于 dev-notes.md

`dev-notes.md` 是整理仓库之前的原始工程笔记，正文未做结构改动，因此文中出现的 `src/`、`res/`、`out/`、`build.sh`、`sdk/` 等路径仍是**整理前的扁平布局**。当前仓库请以 [README.md](../README.md) 和 [build.md](build.md) 为准。

笔记里的真实抓包示例均已对设备唯一标识（MAC、CPU ID、配对号、车型码）做脱敏。
