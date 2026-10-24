#!/usr/bin/env bash
# Muse 壳 APK 手工构建流水线（无 Gradle）：aapt2 -> javac -> d8 -> zipalign -> apksigner
set -euo pipefail
export HOME=/home/hatch
ROOT="$HOME/workspace/muse-android-shell"
APP="$ROOT/app"
TC="$ROOT/toolchain"
BT="$TC/android-16"
JAR="$TC/android-35/android.jar"
export JAVA_HOME="$HOME/.local/jdk-21"
export PATH="$JAVA_HOME/bin:$BT:$PATH"
OUT="$ROOT/build"
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/core-classes" "$OUT/core-dex" "$OUT/assets"

echo '[core] build vscreen core dex -> assets/core.dex'
find "$ROOT/core/src" -name '*.java' > "$OUT/core-sources.txt"
javac --release 8 -cp "$JAR" -d "$OUT/core-classes" @"$OUT/core-sources.txt"
d8 --lib "$JAR" --min-api 26 --output "$OUT/core-dex" $(find "$OUT/core-classes" -name '*.class')
cp "$OUT/core-dex/classes.dex" "$OUT/assets/core.dex"

echo '[1/6] aapt2 compile+link'
aapt2 compile --dir "$APP/res" -o "$OUT/compiled.zip"
aapt2 link -o "$OUT/app-unsigned.apk" -I "$JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$OUT/gen" \
  -A "$OUT/assets" \
  --min-sdk-version 26 --target-sdk-version 35 \
  "$OUT/compiled.zip"

echo '[secret] generate RelaySecret.java (breeno token + muse2api key)'
python3 - "$ROOT/server/tokens.json" "$APP/src/app/ruisnow/muse/hook/RelaySecret.java" "$HOME/workspace/goals/muse-android-shell/hidden_files/muse2api-key.txt" <<'PYEOF'
import json, sys
try:
    tok = json.load(open(sys.argv[1])).get("breeno", "")
except Exception:
    tok = ""
try:
    key = open(sys.argv[3]).read().strip()
except Exception:
    key = ""
src = ("package app.ruisnow.muse.hook;\n\n"
       "/** Generated at build time by build.sh - do not commit, do not copy to the public mirror. */\n"
       "final class RelaySecret {\n"
       "    static final String BASE = \"https://phone.ruisnow.de\";\n"
       "    static final String TOKEN = \"" + tok + "\";\n"
       "    static final String MUSE2API_URL = \"https://muse2api.ruisnow.de/v1/chat/completions\";\n"
       "    static final String MUSE2API_KEY = \"" + key + "\";\n"
       "}\n")
open(sys.argv[2], "w").write(src)
print("RelaySecret generated, token present:", bool(tok), "| muse2api key present:", bool(key))
PYEOF

echo '[2/6] javac'
find "$APP/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
LIBS="$(ls "$APP"/libs/*.jar "$APP"/compile-libs/*.jar 2>/dev/null | paste -sd: -)"
javac --release 8 -cp "$JAR:$LIBS" -d "$OUT/classes" @"$OUT/sources.txt"

echo '[3/6] d8'
d8 --lib "$JAR" --min-api 26 --output "$OUT/dex" $(find "$OUT/classes" -name '*.class') "$APP"/libs/*.jar

echo '[4/6] package dex + xposed metadata'
cp "$OUT/app-unsigned.apk" "$OUT/app-dex.apk"
(cd "$OUT/dex" && zip -q -u "$OUT/app-dex.apk" classes.dex)
# libxposed module metadata (compile-libs/ API stubs are compile-only and are
# deliberately NOT dexed above: the framework provides the API at runtime).
(cd "$APP/xposed-meta" && zip -q -r "$OUT/app-dex.apk" META-INF)

echo '[5/6] zipalign'
zipalign -p -f 4 "$OUT/app-dex.apk" "$OUT/app-aligned.apk"

echo '[6/6] sign'
KS="$ROOT/muse-shell.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias muse -keyalg RSA -keysize 2048 \
    -validity 3650 -storepass "$(cat "$ROOT/.ks-pass")" -keypass "$(cat "$ROOT/.ks-pass")" \
    -dname "CN=Muse Shell, O=ruisnow" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass "pass:$(cat "$ROOT/.ks-pass")" \
  --out "$ROOT/MuseShell.apk" "$OUT/app-aligned.apk"
apksigner verify "$ROOT/MuseShell.apk"
aapt2 dump badging "$ROOT/MuseShell.apk" | head -4
ls -la "$ROOT/MuseShell.apk"
echo BUILD_OK
