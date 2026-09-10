#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
revision=ba532298de7b28cfe854aea83d061369d13ca290
src="$PWD/.native/byedpi"
if [[ ! -d "$src/.git" ]]; then
 mkdir -p .native
 git clone --no-checkout https://github.com/hufrea/byedpi.git "$src"
fi
git -C "$src" fetch origin "$revision"
git -C "$src" checkout --detach --force "$revision"
[[ "$(git -C "$src" rev-parse HEAD)" == "$revision" ]]
python3 tools/adapt-dpi.py "$src"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
bin="$sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin"
for pair in 'arm64-v8a aarch64-linux-android26' 'armeabi-v7a armv7a-linux-androideabi26' 'x86_64 x86_64-linux-android26'; do
 read -r abi target <<< "$pair"
 mkdir -p "app/src/main/jniLibs/$abi"
 "$bin/${target}-clang" -O2 -D_GNU_SOURCE -fPIE -pie -Wl,-z,max-page-size=16384 -I"$src" "$src"/{packets,main,conev,proxy,desync,mpool,extend}.c -o "app/src/main/jniLibs/$abi/liboldi_dpi.so"
done
mkdir -p app/src/main/assets/third-party
python3 - <<'PY'
from pathlib import Path
src=Path('.native/byedpi')
Path('app/src/main/assets/third-party/byedpi.txt').write_text('ByeDPI 17.3\nhttps://github.com/hufrea/byedpi\nPinned ba532298de7b28cfe854aea83d061369d13ca290\nOldi adaptation: private Unix listener, protected sockets, no packet logging.\n\n'+(src/'LICENSE').read_text())
PY
