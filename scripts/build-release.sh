#!/usr/bin/env bash
# Build a release APK for EasyUVCStreamer (UVC 立传).
#
# Usage:
#   ./scripts/build-release.sh
#   ./scripts/build-release.sh --clean
#   ./scripts/build-release.sh --install
#   ./scripts/build-release.sh --install PA921CMGK5130451G
#   ./scripts/build-release.sh --out ./dist
#
# Notes:
#   - Signs with signing/release keystore when keystore.properties exists.
#   - Requires Android SDK (local.properties / ANDROID_HOME) and vcpkg (VCPKG_ROOT).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DO_CLEAN=0
DO_INSTALL=0
INSTALL_SERIAL=""
OUT_DIR=""

usage() {
  sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --clean) DO_CLEAN=1; shift ;;
    --install)
      DO_INSTALL=1
      shift
      if [[ $# -gt 0 && "$1" != -* ]]; then
        INSTALL_SERIAL="$1"
        shift
      fi
      ;;
    --out)
      [[ $# -ge 2 ]] || { echo "error: --out needs a directory" >&2; exit 2; }
      OUT_DIR="$2"
      shift 2
      ;;
    *)
      echo "error: unknown arg: $1" >&2
      usage 2
      ;;
  esac
done

log() { printf '==> %s\n' "$*"; }
die() { echo "error: $*" >&2; exit 1; }

resolve_java_home() {
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    return
  fi
  local candidates=(
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    "${HOME}/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  )
  local c
  for c in "${candidates[@]}"; do
    if [[ -x "${c}/bin/java" ]]; then
      export JAVA_HOME="$c"
      return
    fi
  done
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    local jh
    jh="$(/usr/libexec/java_home 2>/dev/null || true)"
    if [[ -n "$jh" && -x "${jh}/bin/java" ]]; then
      export JAVA_HOME="$jh"
      return
    fi
  fi
  die "JAVA_HOME not found (install JDK 17+ or Android Studio)"
}

resolve_android_sdk() {
  if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]]; then
    export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
    return
  fi
  if [[ -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT" ]]; then
    export ANDROID_HOME="$ANDROID_SDK_ROOT"
    return
  fi
  if [[ -f "$ROOT/local.properties" ]]; then
    local sdk
    sdk="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | tail -1 | tr -d '\r' | sed 's/\\\\/\//g')"
    if [[ -n "$sdk" && -d "$sdk" ]]; then
      export ANDROID_HOME="$sdk"
      export ANDROID_SDK_ROOT="$sdk"
      return
    fi
  fi
  local fallback="${HOME}/Library/Android/sdk"
  if [[ -d "$fallback" ]]; then
    export ANDROID_HOME="$fallback"
    export ANDROID_SDK_ROOT="$fallback"
    return
  fi
  die "Android SDK not found (set ANDROID_HOME or sdk.dir in local.properties)"
}

resolve_vcpkg() {
  if [[ -z "${VCPKG_ROOT:-}" ]]; then
    export VCPKG_ROOT="${HOME}/vcpkg"
  fi
  if [[ ! -d "$VCPKG_ROOT" ]]; then
    die "VCPKG_ROOT missing: $VCPKG_ROOT (clone vcpkg or export VCPKG_ROOT)"
  fi
}

resolve_java_home
resolve_android_sdk
resolve_vcpkg

export PATH="${JAVA_HOME}/bin:${ANDROID_HOME}/platform-tools:${PATH}"

log "ROOT       = $ROOT"
log "JAVA_HOME  = $JAVA_HOME"
log "ANDROID    = $ANDROID_HOME"
log "VCPKG_ROOT = $VCPKG_ROOT"
java -version 2>&1 | head -1 || true

GRADLE=(./gradlew --no-daemon)
if [[ "$DO_CLEAN" -eq 1 ]]; then
  log "Cleaning..."
  "${GRADLE[@]}" clean
fi

log "Building :app:assembleRelease..."
"${GRADLE[@]}" :app:assembleRelease

APK_SRC="$ROOT/app/build/outputs/apk/release/app-release.apk"
[[ -f "$APK_SRC" ]] || die "APK not found: $APK_SRC"

SIZE="$(du -h "$APK_SRC" | awk '{print $1}')"
log "Built $APK_SRC ($SIZE)"

if [[ -n "$OUT_DIR" ]]; then
  mkdir -p "$OUT_DIR"
  VERSION_NAME="${ORG_GRADLE_PROJECT_releaseVersionName:-}"
  if [[ -z "$VERSION_NAME" ]]; then
    VERSION_NAME="$(sed -n 's/.*versionName *= *.*?: *"\([^"]*\)".*/\1/p' "$ROOT/app/build.gradle.kts" | head -1)"
  fi
  VERSION_NAME="${VERSION_NAME:-1.0}"
  STAMP="$(date +%Y%m%d-%H%M%S)"
  APK_DST="$OUT_DIR/EasyUVCStreamer-${VERSION_NAME}-${STAMP}-release.apk"
  cp -f "$APK_SRC" "$APK_DST"
  log "Copied to $APK_DST"
  APK_SRC="$APK_DST"
fi

if [[ "$DO_INSTALL" -eq 1 ]]; then
  command -v adb >/dev/null 2>&1 || die "adb not found (install platform-tools)"
  ADB=(adb)
  if [[ -n "$INSTALL_SERIAL" ]]; then
    ADB=(adb -s "$INSTALL_SERIAL")
  fi
  log "Installing via ${ADB[*]}..."
  "${ADB[@]}" devices -l
  "${ADB[@]}" install -r "$APK_SRC"
  log "Install done"
fi

log "OK"
printf '%s\n' "$APK_SRC"
