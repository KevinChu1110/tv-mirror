# AGENTS.md — 給 AI 協作者的說明

這份文件描述這個專案的結構、協定、已知問題與規則。修改前請先讀完。詳細的歷史排查紀錄在 `docs/DESIGN.md`（附錄 B）。

## 1. 架構（一句話）

電視（WebView 載入 `/call-tv`）與手機（`/call-join`、`/mic`）透過伺服器交換 WebRTC 信令；媒體直連或經 TURN。伺服器不處理媒體。

## 2. 伺服器（`server.js`）

- 只用 Node 內建模組。埠 `PORT`（預設 8787）。`BEHIND_PROXY=1` 時由反向代理處理 HTTPS。
- **房間**：`roomId` 由電視產生並存在電視本機（`tvRoomId`），`/register-code` 把 6 位數配對碼綁到房間。
- **配對碼**：`pairs.json`（在 `data/`，不進 git）。同一房間只保留最新的碼。`/join-code` 錯誤過多會暫停一分鐘。
- **信令**：
  - `GET /events?role=<peerId>&room=<roomId>`：SSE 訂閱，斷線會排隊保存訊息。
  - `POST /send?room=<roomId>`：body `{to, from, type, data}`，`to` 必須是合法的 peerId（`[A-Za-z0-9_-]{2,64}`）。
  - 目前的 peerId：`tv`（電視）、`join`（手機加入，單一角色）、`mic`（客廳麥克風）。
- **TURN**：`GET /turn` 回傳 STUN＋TURN 與 1 小時有效的臨時帳密（coturn REST 格式，HMAC-SHA1）。
- `/rooms` 是舊的一次性配對碼流程，已不再使用。

## 3. 信令訊息

| type | 方向 | data |
|---|---|---|
| `hello` | 手機 → tv | `{deviceId, label}`（`from` 為 `join` 或 `mic`） |
| `denied` | tv → 手機 | 無（拒絕加入） |
| `offer` | tv → 手機 | `{desc, restart}`（`desc` 為 SDP；`restart` 為 ICE 重啟） |
| `answer` | 手機 → tv | SDP |
| `ice` | 雙向 | ICE candidate |
| `bye` | 任一 → 對方 | 無（掛斷；電視收到後回待命） |

電視是 offer 端，手機是 answer 端（`call-join`、`mic` 的 `onSignal` 只處理 offer）。

## 4. 電視端（`call-tv.html`）重點

- 核准卡片獨立於覆蓋層（`.card`），預設焦點在「拒絕」，30 秒倒數自動拒絕。
- 白名單存在 `approvedDevices`（localStorage），以手機的 `deviceId` 為鍵。
- 媒體：鏡頭用 `getUserMedia`；**麥克風走原生橋接**（見第 6 節），不要改回 WebView 麥克風，除非硬體問題已修好。
- 通話中 `ensureMedia()` 會在麥克風軌道結束時自動改用原生橋接重新接上。
- 統計列預設隱藏；對家人只顯示三段式狀態（畫面順暢／網路不穩，畫面可能變模糊／連線中斷，正在重連）。

## 5. 手機端（`call-join.html`）重點

- 名字只問一次（`displayName`）；配對碼記在 `pairCode`；裝置識別碼 `deviceId`。
- 信令依序處理（`enqueue`），ICE 候選在 remoteDescription 設定前會暫存。
- 重新加入最多 5 次，退避 3～48 秒；超過就結束並提示。
- `tapSound`（點一下開始聽）只在真的被自動播放政策擋下時顯示。
- 權限錯誤用 `friendlyError()` 轉成中文說明，並用 `showPermissionHelp()` 顯示分平台步驟。

## 6. 電視麥克風：為什麼有原生橋接

- 在這台電視（MiTV-MSOR0，Android 11）的 WebView 中，麥克風通話時會回傳 0 筆資料，Chromium 約 15 秒後結束音軌；通話中音訊位元組數為 0。
- 原生 `AudioRecord` 錄 USB 鏡頭麥克風是正常的。
- `NativeMic`（`MainActivity.java`）用 AudioRecord 錄 48 kHz 單聲道，每 100 毫秒 `evaluateJavascript` 送給 `window.nativePcm(base64, rate)`；網頁用 `MediaStreamTrackGenerator` + `AudioData` 變成音軌。
- 峰值限制在 `nativePcm` 內（超過約 75% 滿刻度就整段等比縮小）。
- AudioRecord 只在 App 在前景時能錄音；螢幕保護程式會搶焦點，所以部署說明要求停用保護程式。

## 7. TURN 與安全（重要）

- coturn 必須能讀到設定檔（`/etc/turnserver.conf` 要對執行帳號可讀）。權限錯誤會退回預設（**無認證＝open relay**）。
- 每次改動 TURN 設定，都要從外部驗證：匿名 Allocate → 0x0113；帶 `/turn` 的臨時帳密 → 0x0103。
- 不要在程式、文件、log 中寫入真實密鑰或 IP。`deploy.env` 與 `certs/`、`data/` 不進 git。
- 電視的 WebView 遠端除錯只在 debug 版開啟（`BuildConfig.DEBUG`）。
- 白名單的 `deviceId` 是手機自報的，只適合家用，不要當成身分驗證。

## 8. 部署規則

- **靜態檔案**（`public/*`）改動：`--web-only` 上傳即可，伺服器直接讀檔，不需重啟。但**電視要重開 App 才會載入新版頁面**，而且會打斷正在看電視的人。
- **`server.js`** 改動：要重啟 `tv-mirror`，會中斷現有通話（約 2 秒）。通話中不要做。
- **電視 APK** 改動：要 `adb install -r` 並重開 App。
- 部署前先 `node --check` 與抽出頁面 script 做語法檢查（`deploy.sh` 會做）。

## 9. 測試方法（沒有電視時）

- 用兩個瀏覽器分頁模擬：電視頁與手機頁，各自覆寫 `navigator.mediaDevices.getUserMedia`，回傳 canvas 畫面與振盪器聲音的假串流。
- 協商與掛斷、核准卡片、重新加入等流程都能用這個方法測。
- 要測電視硬體（真實鏡頭、麥克風、H.264 硬體編碼）必須有電視在線，且要有人在電視前按核准。
- 手機實機（iOS 背景、鎖屏、自動播放）無法在模擬中測。

## 10. 已知問題（未完全解決）

- 電視視訊目前是 VP8，未確認 H.264 硬體編碼在 WebView 中是否生效（`call-tv` 會偏好 H.264，但硬體可能不支援）。
- 手機 iOS 背景切換與鎖屏後的行為，需要實機確認。
- 第 6 節的原生橋接只在這台電視驗證過；換電視要重新測。

## 11. 規則（給 AI）

- 不要把任何機密、IP、網域、個人帳號寫進程式或文件；需要時用佔位符（`<SERVER_IP>`、`<YOUR_DOMAIN>`）。
- 不要在通話中重啟服務或重開電視 App。
- 任何會開放防火牆、重啟正式服務、改動 TURN 設定的動作，都要先取得負責人明確同意。
- 文件與程式的註解、UI 文字使用繁體中文。
