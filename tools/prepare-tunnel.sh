#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
src="$PWD/.native/hev-socks5-tunnel"
revision=9a06bc6e7989da54e3d32ff701ef7a7ce4995d3a
if [[ ! -d "$src/.git" ]]; then
 mkdir -p .native
 git clone --no-checkout https://github.com/heiher/hev-socks5-tunnel.git "$src"
fi
git -C "$src" fetch origin "$revision"
git -C "$src" checkout --detach "$revision"
[[ "$(git -C "$src" rev-parse HEAD)" == "$revision" ]]
git -C "$src" submodule update --init --recursive --depth 1
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
"$sdk/ndk/28.2.13676358/ndk-build" -C "$src" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=Android.mk NDK_APPLICATION_MK=Application.mk APP_PLATFORM=android-26 APP_ABI='arm64-v8a armeabi-v7a x86_64' APP_MODULES=hev-socks5-tunnel -j2
for abi in arm64-v8a armeabi-v7a x86_64; do
 mkdir -p "app/src/main/jniLibs/$abi"
 cp "$src/libs/$abi/libhev-socks5-tunnel.so" "app/src/main/jniLibs/$abi/"
done
mkdir -p app/src/main/assets/third-party
python3 - <<'PY'
from pathlib import Path
root=Path('.native/hev-socks5-tunnel')
texts=['hev-socks5-tunnel 2.17.1; source pinned at 9a06bc6e7989da54e3d32ff701ef7a7ce4995d3a\nhttps://github.com/heiher/hev-socks5-tunnel\n']
for folder in [root,*sorted((root/'third-part').iterdir())]:
 if not folder.is_dir():continue
 for name in ('LICENSE','LICENSE.md','COPYING','COPYING.txt'):
  p=folder/name
  if p.is_file():texts.append(str(p.relative_to(root))+'\n'+p.read_text(errors='replace'))
Path('app/src/main/assets/third-party/local-tunnel.txt').write_text('\n\n'.join(texts))
PY
