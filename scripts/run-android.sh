#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_ID="com.rjnsdev.linklift.app"
ACTIVITY_NAME="${APP_ID}.MainActivity"

resolve_sdk_dir() {
  local sdk_dir=""

  if [[ -f "${ROOT_DIR}/local.properties" ]]; then
    while IFS='=' read -r key value || [[ -n "${key:-}" ]]; do
      if [[ "${key}" == "sdk.dir" ]]; then
        sdk_dir="${value//\\:/:}"
        sdk_dir="${sdk_dir//\\\\/\\}"
        break
      fi
    done < "${ROOT_DIR}/local.properties"
  fi

  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    sdk_dir="${ANDROID_SDK_ROOT}"
  elif [[ -n "${ANDROID_HOME:-}" ]]; then
    sdk_dir="${ANDROID_HOME}"
  fi

  if [[ -z "${sdk_dir}" ]]; then
    echo "Android SDK path not found. Set ANDROID_SDK_ROOT or create local.properties with sdk.dir=..." >&2
    exit 1
  fi

  echo "${sdk_dir}"
}

SDK_DIR="$(resolve_sdk_dir)"
ADB="${SDK_DIR}/platform-tools/adb"

if [[ ! -x "${ADB}" ]]; then
  echo "adb not found at ${ADB}" >&2
  exit 1
fi

"${ADB}" start-server >/dev/null

CONNECTED_DEVICES="$("${ADB}" devices | awk 'NR > 1 && $2 == "device" { count += 1 } END { print count + 0 }')"

if [[ "${CONNECTED_DEVICES}" -eq 0 ]]; then
  echo "No Android device detected. Start an emulator or connect a device, then run this again." >&2
  exit 1
fi

"${ROOT_DIR}/gradlew" :app:installDebug
"${ADB}" shell am start -n "${APP_ID}/${ACTIVITY_NAME}"
