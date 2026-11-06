# 构建与签名

## 为什么没有 Gradle

这个工程刻意保持最小：只用 `aapt2 / javac / d8 / apksigner` 四个工具，不需要 Android Studio、不需要 Gradle Wrapper、不需要联网拉依赖。源码布局虽然采用了 Android 标准的 `app/src/main/{java,res}`，但**没有 `build.gradle`**，请勿用 Android Studio 直接「Import Project」。

## 环境要求

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| JDK | 17（编译目标 `--source 8 --target 8`） | `javac`、`java`、`keytool` |
| Android platform | API 34（`android.jar`） | 编译 classpath |
| Android build-tools | 34 | `aapt2`、`zipalign`、`lib/d8.jar`、`lib/apksigner.jar` |
| Python | 3.x | 把 `classes.dex` 塞进 APK（`scripts/add_dex.py`） |
| Bash | — | 构建脚本（Windows 用 Git Bash / MSYS2） |

## 准备工具链

把上述工具链放到仓库根的 `sdk/` 目录（**该目录已被 `.gitignore` 排除，绝不会入库**），保持如下结构：

```
sdk/
├── jdk-17.x/               bin/javac(.exe), bin/java(.exe), bin/keytool(.exe)
├── android-34-ext12/       android.jar
└── android-14/             aapt2(.exe), zipalign(.exe), lib/d8.jar, lib/apksigner.jar
```

也可以用环境变量指定别处：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `BLEKEY_SDK_DIR` | `sdk` | 工具链根目录 |
| `BLEKEY_JDK_DIR` | `$JAVA_HOME` 或 `$SDK_DIR/jdk-17.0.20.1+1` | JDK 根目录 |
| `BLEKEY_ANDROID_JAR` | `$SDK_DIR/android-34-ext12/android.jar` | `android.jar` |
| `BLEKEY_BUILD_TOOLS` | `$SDK_DIR/android-14` | build-tools 目录 |
| `PYTHON` | `python` | Python 解释器 |

> **Windows + 中文工程路径注意**：`aapt2`、`zipalign`、`apksigner` 是原生程序，命令行参数里出现中文路径会因编码问题报 `failed to open directory`。构建脚本因此固定 `cd` 到仓库根、一律使用相对 ASCII 路径。若你的工具链放在别处，请确认 `BLEKEY_*` 路径**不含非 ASCII 字符**，或直接用仓库根 `sdk/` 目录。

## 签名

仓库**不含任何私钥**。首次构建先生成一份本地调试证书：

```bash
bash scripts/setup-signing.sh
# 默认写到 ~/.android/debug.keystore；也可指定路径：
bash scripts/setup-signing.sh /path/to/my-debug.keystore
```

构建时通过环境变量注入（口令默认值 `android` / 别名 `androiddebugkey`）：

| 变量 | 默认值 |
| --- | --- |
| `BLEKEY_KEYSTORE` | `$HOME/.android/debug.keystore` |
| `BLEKEY_KS_PASS` | `android` |
| `BLEKEY_KEY_PASS` | 同 `BLEKEY_KS_PASS` |
| `BLEKEY_KEY_ALIAS` | `androiddebugkey` |

> 换成自己的证书后，产出的 APK 与 `release/` 里预编译包的签名不同，覆盖安装需先卸载旧包。
> 正式发布请使用独立保管的发布证书，**不要**把 keystore、口令或 `signing.properties` 提交到仓库。

## 构建

```bash
bash scripts/build.sh
```

七个步骤：

1. `aapt2 compile --dir app/src/main/res` → 编译资源
2. `aapt2 link --java build/gen` → 生成 `base.apk` 与 `R.java`
3. `javac` 编译 `R.java` + `app/src/main/java/com/blekey/rdb/*.java`
4. `d8` 转 `classes.dex`
5. `scripts/add_dex.py` 把 dex 追加进 APK
6. `zipalign -p 4` 对齐
7. `apksigner sign` 签名 → `release/BleKey-<versionName>.apk`

版本号、minSdk、targetSdk 都从 `AndroidManifest.xml` 读取，不写死在脚本里。中间产物在 `build/`（已忽略），签名附属的 `.idsig` 文件也已忽略。

## 源码模块

| 文件 | 职责 |
| --- | --- |
| `Proto.java` | 协议：组帧 / 解帧 / TLV / AES / 密钥派生 |
| `Prefs.java` | 本地配置（SharedPreferences） |
| `BleKeyService.java` | 前台服务：`autoConnect` 常驻 + 鉴权 + 指令收发 |
| `MainActivity.java` | 界面：主页 + 高级设置 + 阈值配置（纯代码 UI） |
| `Ui.java` | 设计令牌与通用控件（双主题） |
| `IconView.java` / `RingView.java` / `ToggleView.java` | 自绘图标 / 环形电量 / 开关 |
| `BootReceiver.java` | 开机自启 |
| `KeepAlive.java` / `KeepAliveJob.java` / `KeepAliveReceiver.java` | 保活兜底 |

`tools/GenIcon.java` 生成启动器图标的五档 PNG 与桌面效果模拟图；`tools/Zoom.java` 用于放大截图看小图标细节。改图标需同步修改 `GenIcon.java` 与 `res/drawable/ic_launcher_foreground.xml`（坐标基准均为 108×108）。

## 常见问题

| 现象 | 原因 / 处理 |
| --- | --- |
| `aapt2: failed to open directory` | 命令行路径含中文或非 ASCII 字符，见上文 |
| `link` 报 `@mipmap/ic_launcher` 解析不到 | 漏了 `aapt2 compile` 的 `res.zip` 输入，或没传 `--java` |
| `找不到 R.java` | 1.3 起 Java 引用自身资源，必须让 `link` 生成 `R.java` 并作为 `javac` 输入 |
| `apksigner` 报密钥别名 / 口令错误 | 检查 `BLEKEY_KEY_ALIAS` / `BLEKEY_KS_PASS`，或重新运行 `setup-signing.sh` |
| 缺 `build/tools` 相关报错 | 确认 build-tools 34 的 `lib/d8.jar`、`lib/apksigner.jar` 完整 |
