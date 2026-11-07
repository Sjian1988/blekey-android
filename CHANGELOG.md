# 更新日志

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。版本号以 `app/src/main/AndroidManifest.xml` 中的 `versionName` / `versionCode` 为准，产物为 `release/BleKey-<versionName>.apk`。

> v1.0 ~ v1.2 属于快速内部迭代，当时未逐版留存变更记录；此处按最终形态归纳，不做事后补写。

## [1.4] — 当前版本（versionCode 5）

- 按压反馈重做：按下 80ms 内缩至 0.94（宽扁控件 0.97），透明度同步压到 0.78，松手 160ms 回弹，并触发一次轻触反馈。原先只有一个没有过渡的 alpha 跳变，手指按下去的几十毫秒里几乎看不出来
- 按下态边界处理：手指滑出控件范围、`ACTION_CANCEL` 都会立即复位，列表滚动时不会留下按下残影
- 缩放幅度按控件形状区分：主操作块（约 110×84dp）缩 6% 有下陷感，设置行这类宽扁元素只缩 3%，避免整条塌下去

## [1.3] — versionCode 4

- 通知栏小图标改为自绘 vector（`ic_stat_blekey`），Java 首次引用自身资源，构建流程加入 `aapt2 link --java` 生成 `R.java`
- 界面重排版：状态胶囊、环形电量、后备箱单独强调、卡片描边；新增「高级设置」「靠近自动开锁」两个二级页
- 双主题跟随系统深色模式，设计令牌集中在 `Ui.java`
- 常驻通知第三个按钮改为「后备箱」，让位入口保留在 App 内
- 增加车机电量 / 电压解析（`cmd=2` 响应 TLV tag 23 / 32）
- 增加舒适进入参数回读与阈值下发（`cmd=7 key=32 / key=0`），写后重读校准
- 解析车机主动推送的 RSSI（`cmd=4 key=107`）用于状态栏刷新
- 确定开锁节奏：点一次「解锁」连发 2 帧、间隔 1000ms；落锁后 15 秒静默不重连
- 明确否掉语音助手拉起 App 的方案，并移除公开的 `blekey://trunk` 深链（避免无关动作触发不可撤销的机械动作）

## [1.2]

- 引入 `res/` 资源目录与 adaptive launcher icon（vector 前景 + 渐变底色 + 五档 PNG 兜底）
- 构建脚本新增 `aapt2 compile --dir res` 步骤

## [1.0 — 1.1] — 早期迭代

- 最小可用链路：`autoConnect` 常驻连接、GATT 服务发现、鉴权握手、解锁 / 落锁指令
- 前台服务 + 开机自启 + JobScheduler / 闹钟兜底保活
- 通知栏解锁入口、让位机制、运行日志
- 协议实现：`Proto.java` 组帧 / 解帧 / TLV / AES / 密钥派生

---

## 待办（Unreleased）

- 实车验证：车门实际开 / 关、通知栏后备箱按钮、落锁后断电与静默重连、`autoConnect` 实际响应时延
- 舒适进入参数写入（7/0）的实车写入结果确认
- 多车型 / 多车盒固件版本的兼容性矩阵
- 正式发布签名与 GitHub Releases 分发
