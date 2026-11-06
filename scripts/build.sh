#!/usr/bin/env bash
# BleKey 一键构建。
#
# 这个工程刻意不使用 Gradle：整个 App 只依赖 aapt2 / javac / d8 / apksigner，
# 不需要 Android Studio，也不需要联网拉依赖。工具链请自备，见 docs/build.md。
#
# 工具链与签名密钥全部通过环境变量注入，仓库里不含任何私钥或 SDK 二进制。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# aapt2 / zipalign / apksigner 是原生程序，命令行参数里出现中文路径会因编码
# 问题打不开文件。因此下面一律使用相对仓库根的 ASCII 路径，工作目录固定在 ROOT。
APP="app/src/main"
MANIFEST="$APP/AndroidManifest.xml"
BUILD="build"                # 中间产物，已在 .gitignore 中排除
OUT_DIR="release"
PYTHON="${PYTHON:-python}"

# ---------------------------------------------------------------- 工具链
# 优先级：显式环境变量 > 仓库根下的 sdk/（不入库）
# Windows 且工程路径含中文时，请把工具链放到仓库根 sdk/ 下走相对路径。
SDK_DIR="${BLEKEY_SDK_DIR:-sdk}"
JDK_DIR="${BLEKEY_JDK_DIR:-${JAVA_HOME:-$SDK_DIR/jdk-17.0.20.1+1}}"
ANDROID_JAR="${BLEKEY_ANDROID_JAR:-$SDK_DIR/android-34-ext12/android.jar}"
BUILD_TOOLS="${BLEKEY_BUILD_TOOLS:-$SDK_DIR/android-14}"

# Git Bash / MSYS 下可执行文件带 .exe，Linux / macOS 下不带
exe() { if [[ -f "$1.exe" ]]; then printf '%s' "$1.exe"; else printf '%s' "$1"; fi; }

JAVAC="$(exe "$JDK_DIR/bin/javac")"
JAVA="$(exe "$JDK_DIR/bin/java")"
AAPT2="$(exe "$BUILD_TOOLS/aapt2")"
ZIPALIGN="$(exe "$BUILD_TOOLS/zipalign")"
D8_JAR="$BUILD_TOOLS/lib/d8.jar"
APKSIGNER_JAR="$BUILD_TOOLS/lib/apksigner.jar"

# ---------------------------------------------------------------- 签名
# 默认用 Android SDK 的标准调试证书；也可用 BLEKEY_KEYSTORE 指向自己的 keystore
KEYSTORE="${BLEKEY_KEYSTORE:-$HOME/.android/debug.keystore}"
KS_PASS="${BLEKEY_KS_PASS:-android}"
KEY_PASS="${BLEKEY_KEY_PASS:-$KS_PASS}"
KS_ALIAS="${BLEKEY_KEY_ALIAS:-androiddebugkey}"

# ---------------------------------------------------------------- 前置检查
for f in "$MANIFEST" "$ANDROID_JAR" "$D8_JAR" "$APKSIGNER_JAR" \
         "$JAVAC" "$JAVA" "$AAPT2" "$ZIPALIGN"; do
    if [[ ! -e "$f" ]]; then
        echo "缺少工具链文件: $f"
        echo "请设置 BLEKEY_SDK_DIR / BLEKEY_JDK_DIR，详见 docs/build.md"
        exit 1
    fi
done
if [[ ! -f "$KEYSTORE" ]]; then
    echo "未找到签名密钥: $KEYSTORE"
    echo "执行 bash scripts/setup-signing.sh 生成，或用 BLEKEY_KEYSTORE 指定已有密钥"
    exit 1
fi

# ---------------------------------------------------------------- 版本信息
VC=$(grep -o 'android:versionCode="[0-9]*"' "$MANIFEST"); VC=${VC#*\"}; VC=${VC%%\"*}
VN=$(grep -o 'android:versionName="[^"]*"' "$MANIFEST"); VN=${VN#*\"}; VN=${VN%%\"*}
MIN=$(grep -o 'android:minSdkVersion="[0-9]*"' "$MANIFEST"); MIN=${MIN#*\"}; MIN=${MIN%%\"*}
TGT=$(grep -o 'android:targetSdkVersion="[0-9]*"' "$MANIFEST"); TGT=${TGT#*\"}; TGT=${TGT%%\"*}
OUT="$OUT_DIR/BleKey-$VN.apk"

echo "BleKey $VN (versionCode=$VC, minSdk=$MIN, targetSdk=$TGT)"
echo "工具链: $BUILD_TOOLS"
echo "签名:   $KEYSTORE"

# ---------------------------------------------------------------- 构建
rm -rf "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/res.zip" "$BUILD/base.apk" \
       "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/gen" "$OUT_DIR"

echo "[1/7] aapt2 compile 资源"
"$AAPT2" compile --dir "$APP/res" -o "$BUILD/res.zip"

echo "[2/7] aapt2 link (生成 R.java)"
"$AAPT2" link -o "$BUILD/base.apk" -I "$ANDROID_JAR" \
    --manifest "$MANIFEST" \
    --java "$BUILD/gen" \
    --min-sdk-version "$MIN" --target-sdk-version "$TGT" \
    --version-code "$VC" --version-name "$VN" \
    "$BUILD/res.zip"

echo "[3/7] javac 编译 Java"
"$JAVAC" -source 8 -target 8 -nowarn -encoding UTF-8 \
    -bootclasspath "$ANDROID_JAR" -d "$BUILD/classes" \
    "$BUILD/gen/com/blekey/rdb/R.java" \
    "$APP/java/com/blekey/rdb/"*.java

echo "[4/7] d8 转 dex"
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
    --min-api "$MIN" --lib "$ANDROID_JAR" --output "$BUILD/dex" \
    "$BUILD/classes/com/blekey/rdb/"*.class

echo "[5/7] 打包 classes.dex"
"$PYTHON" "scripts/add_dex.py" "$BUILD"

echo "[6/7] zipalign"
"$ZIPALIGN" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "[7/7] apksigner 签名"
"$JAVA" -jar "$APKSIGNER_JAR" sign \
    --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
    --ks-key-alias "$KS_ALIAS" --out "$OUT" "$BUILD/aligned.apk"

echo "OK -> $OUT"
ls -la "$OUT"
