#!/usr/bin/env bash
# 生成一份只属于你自己的本地调试签名证书。
#
# 证书、口令、别名一律不进仓库（见 .gitignore）。想换成正式发布证书时，
# 用 BLEKEY_KEYSTORE / BLEKEY_KS_PASS / BLEKEY_KEY_PASS / BLEKEY_KEY_ALIAS
# 四个环境变量指过去即可，构建脚本无需改动。
set -euo pipefail

DEST="${1:-${BLEKEY_KEYSTORE:-$HOME/.android/debug.keystore}}"
PASS="${BLEKEY_KS_PASS:-android}"
KEY_PASS="${BLEKEY_KEY_PASS:-$PASS}"
ALIAS="${BLEKEY_KEY_ALIAS:-androiddebugkey}"

if [[ -f "$DEST" ]]; then
    echo "密钥已存在，未做改动: $DEST"
    exit 0
fi

KEYTOOL=""
if [[ -n "${JAVA_HOME:-}" ]]; then
    if [[ -f "$JAVA_HOME/bin/keytool.exe" ]]; then
        KEYTOOL="$JAVA_HOME/bin/keytool.exe"
    elif [[ -f "$JAVA_HOME/bin/keytool" ]]; then
        KEYTOOL="$JAVA_HOME/bin/keytool"
    fi
fi
if [[ -z "$KEYTOOL" ]] && command -v keytool >/dev/null 2>&1; then
    KEYTOOL="$(command -v keytool)"
fi
if [[ -z "$KEYTOOL" ]]; then
    echo "找不到 keytool，请先安装 JDK 17 或设置 JAVA_HOME"
    exit 1
fi

mkdir -p "$(dirname "$DEST")"
"$KEYTOOL" -genkeypair -v \
    -keystore "$DEST" -storetype PKCS12 \
    -storepass "$PASS" -keypass "$KEY_PASS" -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=BleKey Debug, OU=Development, O=BleKey, C=CN"

echo "OK -> $DEST"
echo "提示：请勿把该文件加入版本库；换机后重新生成本地调试证书即可。"
