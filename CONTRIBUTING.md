# 贡献指南

欢迎车友提交问题、兼容性验证结果和改进。仓库当前为私有、预开源状态，贡献流程会随公开一并简化。

## 提交前

- 先读 [README.md](README.md) 的「范围与免责声明」，确认你的使用场景合规
- 搜一下已有 Issue，避免重复
- **不要提交**任何第三方应用的代码、DEX、APK、反编译产物或脱壳过程资料
- **不要提交**签名密钥、口令、真实 MAC 地址、车机序列号、CPU ID、设备密钥或含这些值的原始日志 / 抓包 / 截图

## 报告问题

请使用 [Bug 报告模板](.github/ISSUE_TEMPLATE/bug_report.yml)，至少给出：

- App 版本、手机型号 / Android 版本 / ROM、车盒固件版本
- 复现步骤与期望 / 实际结果
- `adb logcat -s BleKey` 或 App 内运行日志片段（**已脱敏**）

日志里的 hex 帧很有价值，但请先替换掉所有设备唯一标识。

## 代码约定

- Java 8 语法，编译目标 `-source 8 -target 8`，不使用 Gradle、不引入第三方依赖
- 界面是**纯代码绘制**，布局不进 `res/`；颜色 / 字号 / 间距一律从 `Ui.java` 取令牌，不写死 `0xFFxxxxxx`
- 状态表达不能只靠颜色（圆点 + 文字 + 信号条三重表达），触控目标不小于 44dp
- 改启动器图标需同步 `tools/GenIcon.java` 与 `res/drawable/ic_launcher_foreground.xml`（108×108 坐标基准，图形按可见区 66×66 的 75% 排布）
- 涉及协议改动请同步 [docs/protocol.md](docs/protocol.md)，并说明是抓包实测还是推测
- 版本号改在 `app/src/main/AndroidManifest.xml`，同时在 [CHANGELOG.md](CHANGELOG.md) 记一笔

## 提交 PR

1. Fork 并基于 `main` 建分支
2. 本地跑通 `bash scripts/build.sh`，确认能产出 APK
3. 若改动涉及界面，附浅色 / 深色两张截图（设备标识需脱敏）
4. PR 里说明改动动机、验证方式、影响范围，模板见 [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md)

## 分支与发布

- `main` 保持可构建状态
- 版本号与 `AndroidManifest.xml` 对齐；发布包走 GitHub Releases，不往 `release/` 里堆历史 APK
