#!/usr/bin/env bash
set -euo pipefail

TV_IP="${1:-192.168.1.200}"
HOST_IP="${2:-192.168.1.100}"
QUIZ_URL="http://${HOST_IP}:8090/api/quiz.json"
PKG="com.bing.mitvguard"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DOCKER_CMD="docker"
if ! docker info >/dev/null 2>&1; then
  DOCKER_CMD="sudo docker"
fi

echo "=== 1. 构建 Android Docker 编译环境 ==="
${DOCKER_CMD} build -f "${SCRIPT_DIR}/Dockerfile.build" -t mitv-android-builder "${SCRIPT_DIR}"

echo "=== 2. 编译小米电视专用 APK ==="
mkdir -p "${HOME}/.gradle-mitv-cache"
${DOCKER_CMD} run --rm \
  -v "${SCRIPT_DIR}:/project" \
  -v "${HOME}/.gradle-mitv-cache:/home/gradle/.gradle" \
  -w /project \
  mitv-android-builder \
  gradle assembleDebug --no-daemon

APK_PATH="${SCRIPT_DIR}/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "${APK_PATH}" ]; then
  echo "未找到编译产物: ${APK_PATH}"
  exit 1
fi
echo "编译成功: ${APK_PATH}"

echo "=== 3. 连接小米电视 (${TV_IP}:5555) 并安装 ==="
adb connect "${TV_IP}:5555" || true
if ! adb -s "${TV_IP}:5555" get-state >/dev/null 2>&1; then
  echo "请先确认小米电视已开启 ADB 调试并完成屏幕授权（adb devices 应显示 device）"
  exit 1
fi

if ! adb -s "${TV_IP}:5555" install -r -d "${APK_PATH}"; then
  echo "检测到签名不一致，自动卸载旧版本后重新安装..."
  adb -s "${TV_IP}:5555" uninstall "${PKG}" || true
  adb -s "${TV_IP}:5555" install -r -d "${APK_PATH}"
fi

echo "=== 4. 一次性写入系统级永久权限（断电不丢失） ==="
adb -s "${TV_IP}:5555" shell appops set "${PKG}" SYSTEM_ALERT_WINDOW allow
adb -s "${TV_IP}:5555" shell appops set "${PKG}" RUN_IN_BACKGROUND allow
adb -s "${TV_IP}:5555" shell appops set "${PKG}" RUN_ANY_IN_BACKGROUND allow
adb -s "${TV_IP}:5555" shell appops set "${PKG}" START_FOREGROUND allow
adb -s "${TV_IP}:5555" shell appops set "${PKG}" WAKE_LOCK allow
adb -s "${TV_IP}:5555" shell dumpsys deviceidle whitelist +"${PKG}"

ACC_SVC="${PKG}/.LockAccessibilityService"
CURRENT_ACC=$(adb -s "${TV_IP}:5555" shell settings get secure enabled_accessibility_services | tr -d '\r')
if [ -z "${CURRENT_ACC}" ] || [ "${CURRENT_ACC}" = "null" ]; then
  NEW_ACC="${ACC_SVC}"
elif echo "${CURRENT_ACC}" | grep -q "${ACC_SVC}"; then
  NEW_ACC="${CURRENT_ACC}"
else
  NEW_ACC="${CURRENT_ACC}:${ACC_SVC}"
fi
adb -s "${TV_IP}:5555" shell settings put secure enabled_accessibility_services "${NEW_ACC}"
adb -s "${TV_IP}:5555" shell settings put secure accessibility_enabled 1

echo "=== 5. 清除 stopped 状态、启动服务并下发网页题库地址 (${QUIZ_URL}) ==="
adb -s "${TV_IP}:5555" shell am start -n "${PKG}/.MainActivity"
sleep 2
adb -s "${TV_IP}:5555" shell am broadcast \
  -a "${PKG}.SET_CONFIG" \
  --es quiz_url "${QUIZ_URL}" \
  -n "${PKG}/.ConfigReceiver"

echo "=== 6. 核对开机自启关键标志 (stopped 必须为 false) ==="
adb -s "${TV_IP}:5555" shell "dumpsys package ${PKG} | grep 'User 0:'"

echo "=== 全部完成！请在电视上点击【立即同步网页题库】或【立即测试锁屏答题】验证 ==="
