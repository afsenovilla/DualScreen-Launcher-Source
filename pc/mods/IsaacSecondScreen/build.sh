#!/usr/bin/env bash
# Linux equivalent of build.ps1: cross-compiles winmm.dll with mingw-w64 and
# regenerates the launcher's bundled Isaac payload and manifest.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; root="$(cd "$here/../../.." && pwd)"
cxx=${CXX:-i686-w64-mingw32-g++}
src="$here/src"; out="$here/build/release"; mkdir -p "$out" "$here/build/tests"
common=(-std=c++17 -m32 -Wall -Wextra -Wpedantic -Werror -DWIN32_LEAN_AND_MEAN -DNOMINMAX -DUNICODE -D_UNICODE -I "$src" -static -static-libgcc -static-libstdc++)
"$cxx" "${common[@]}" -O2 "$here/tests/ProtocolTests.cpp" "$src/Protocol.cpp" -o "$here/build/tests/ProtocolTests.exe"
"$cxx" "${common[@]}" -O2 "$here/tests/PanelInputTests.cpp" "$src/PanelInput.cpp" -o "$here/build/tests/PanelInputTests.exe"
"$cxx" "${common[@]}" -O2 -DNDEBUG -shared -Wl,--kill-at -Wl,--no-insert-timestamp -Wl,--dynamicbase -Wl,--nxcompat \
  -o "$out/winmm.dll" "$src"/{Proxy,LuaBridge,NativeState,NativeMap,Mod,BuildIdentity,Protocol,PanelInput,AssetCatalog,AssetCache,BitmapFont,Panel,Log}.cpp \
  "$src/winmm.def" -lws2_32 -lgdi32 -lgdiplus -luser32 -lbcrypt
mod="$root/launcher/src/main/assets/mods/isaac-repentance"; payload="$mod/payload"
rm -rf "$payload"; mkdir -p "$payload/native" "$payload/mod" "$payload/assets"
cp "$out/winmm.dll" "$payload/native/"; cp "$here/mod/main.lua" "$here/mod/metadata.xml" "$payload/mod/"
cp "$here/assets/catalog.tsv" "$payload/assets/"; cp -r "$here/assets/game" "$here/assets/custom" "$payload/assets/"
version=$(grep -o "isaac-native-j460-[0-9.]*" "$here/build.ps1" | head -1)
python3 - "$payload" "$mod/manifest.json" "$version" <<'P'
import hashlib, json, os, sys
payload, manifest, version = sys.argv[1:]
h = lambda p: hashlib.sha256(open(p, 'rb').read()).hexdigest()
files = [{"source": s, "target": t, "sha256": h(os.path.join(payload, s))} for s, t in [
    ("native/winmm.dll", "winmm.dll"), ("mod/main.lua", "mods/DualScreenIsaac/main.lua"),
    ("mod/metadata.xml", "mods/DualScreenIsaac/metadata.xml")]]
assets = sorted(os.path.relpath(os.path.join(d, f), payload).replace(os.sep, '/')
                for d, _, fs in os.walk(os.path.join(payload, "assets")) for f in fs)
files += [{"source": a, "target": "resources/scripts/isaac_second_screen_assets/" + a[len("assets/"):],
           "sha256": h(os.path.join(payload, a))} for a in assets]
json.dump({"version": version, "files": files, "remove": ["resources/scripts/isaac_second_screen.dll"]},
          open(manifest, "w"), indent=4); open(manifest, "a").write("\n")
P
sha256sum "$out/winmm.dll"
