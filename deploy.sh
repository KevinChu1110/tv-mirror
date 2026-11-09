#!/usr/bin/env bash
# 一鍵部署：上傳伺服器與頁面 → 重啟 tv-mirror（約 2 秒中斷）→ 重裝並重開電視 App。
# 用法：./deploy.sh            （全部）
#       ./deploy.sh --web-only （只上傳網頁與伺服器，不碰電視）
#       ./deploy.sh --miccheck （額外安裝並啟動麥克風診斷 App；跑完用 adb logcat -d -s MicCheck 看結果）
set -euo pipefail
cd "$(dirname "$0")"

# 設定檔：deploy.env（參考 deploy.env.example），不進 git
if [ ! -f deploy.env ]; then echo "缺少 deploy.env，請先複製 deploy.env.example 並填寫"; exit 1; fi
set -a; . ./deploy.env; set +a
HOST="$SERVER_HOST"
SSH=(ssh -o BatchMode=yes -o IdentitiesOnly=yes -i "$SSH_KEY")
SCP=(scp -q -o BatchMode=yes -o IdentitiesOnly=yes -i "$SSH_KEY")
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
TV="$TV_ADB"

node --check server.js
node --check public/common.js
for f in call-tv call-join mic; do
  sed -n '/<script>/,/<\/script>/p' "public/$f.html" | sed '1d;$d' > "/tmp/chk-$f.js"
  node --check "/tmp/chk-$f.js"
done
echo "✓ 語法檢查通過"

"${SCP[@]}" server.js "$HOST:$SERVER_DIR/"
"${SCP[@]}" public/common.js public/call-tv.html public/call-join.html public/mic.html public/icon.png "$HOST:$SERVER_DIR/public/"
"${SSH[@]}" "$HOST" 'systemctl restart tv-mirror; sleep 2; systemctl is-active tv-mirror; curl -s -o /dev/null -w "call-tv %{http_code}\n" http://127.0.0.1:8790/call-tv'
echo "✓ 伺服器已更新"

if [[ "${1:-}" == "--web-only" ]]; then exit 0; fi

"$ADB" connect "$TV" >/dev/null
"$ADB" -s "$TV" install -r android/app/build/outputs/apk/debug/app-debug.apk | tail -1
"$ADB" -s "$TV" shell am force-stop tw.kevin.tvcall
sleep 1
"$ADB" -s "$TV" shell am start -n tw.kevin.tvcall/.MainActivity >/dev/null
echo "✓ 電視 App 已重裝並啟動"

if [[ "${1:-}" == "--miccheck" ]]; then
  "$ADB" -s "$TV" shell am force-stop tw.kevin.tvcall   # 讓出麥克風給診斷 App
  "$ADB" -s "$TV" install -r tools/miccheck/app/build/outputs/apk/debug/app-debug.apk | tail -1
  "$ADB" -s "$TV" shell pm grant tw.kevin.miccheck android.permission.RECORD_AUDIO || true
  "$ADB" -s "$TV" logcat -c
  "$ADB" -s "$TV" shell am start -n tw.kevin.miccheck/.MainActivity >/dev/null
  echo "✓ 麥克風診斷已啟動（約 1 分鐘）。結果：$ADB -s $TV logcat -d -s MicCheck"
fi
