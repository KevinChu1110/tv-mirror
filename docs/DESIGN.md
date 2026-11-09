# 客廳視訊（tv-mirror / call）設計稿 v2

日期：2026-11-07　狀態：**Kevin 已核准（2026-11-07）**，P0 開始實作；部署等通話結束

已定案：
- 「手機出聲」為**預設**音訊模式（P4）
- 多方上限 **4 人**
- 分期順序照 §4，但**麥克風排查併入 P0**（miccheck 全矩陣與 WebView 除錯一起上）

範圍：`server.js`、`public/common.js`、`public/call-tv.html`、`public/call-join.html`、`public/mic.html`、`android/…/MainActivity.java`

---

## 0. 一句話結論

目前的一對一已能用，但三件事拖住體驗：**（1）電視用軟體編碼 VP8 720p、（2）斷線就整個拆掉不重連、（3）電視麥克風在 HAL 層讀不到資料**。
建議順序：先做「零風險修補」（不改架構、半小時內可上），再把信令改成「電視為主持人的多方房間」，最後把客廳手機升格為**音訊端**（喇叭＋麥克風都在手機），電視只負責畫面與鏡頭，從根本繞過電視麥克風的問題並解掉回音。

---

## 1. 稽核發現（依影響排序）

### 1.1 流暢度（畫面卡、延遲）

| # | 位置 | 問題 | 建議 |
|---|------|------|------|
| F1 | `call-tv.html` `makePeer()` | 沒設定編碼器偏好，Chromium 預設先排 VP8 → 電視用**軟體**編碼 720p30，2 核 TV 吃不消；iPhone 端 VP8 也是軟解。 | 電視已確認有硬體 H.264 編碼器（`OMX.MS.AVC.Encoder`）。雙方 `transceiver.setCodecPreferences()` 把 H.264 排第一，電視與 iPhone 都走硬體。預期是最大的單一改善。 |
| F2 | 同上 | 送出端沒有 `maxBitrate` / `maxFramerate` / `degradationPreference`。 | 電視：`maxBitrate 1.2 Mbps、maxFramerate 24、degradationPreference 'maintain-framerate'`、`contentHint='motion'`；手機：`maxBitrate 800 kbps`。 |
| F3 | `startStats()` | 只看**接收**端統計，看不出電視自己的編碼是否卡住。 | 加 `outbound-rtp` 的 `framesPerSecond`、`qualityLimitationReason`（cpu / bandwidth）。這一行數字就能判定「卡」是 CPU 還是網路。 |
| F4 | `call-tv.html:429` | `iceConfig()` 非同步載入，若 hello 比 TURN 憑證先到，`pc` 用空的 `iceServers` 建立 → 外網必掛。憑證 TTL 1 小時，電視開一整天後也會過期。 | `startCall()` 內 `await iceConfig()`（每次建 pc 都重抓）。 |
| F5 | `onconnectionstatechange` | `disconnected` 常是暫時性（Wi-Fi 抖動 2–3 秒會自己回 `connected`），現在一碰到就拆 UI、回待命。 | `disconnected` → 等 4 秒仍未恢復 → `pc.restartIce()`；只有 `failed` 才拆。手機端對應：收到 TV 重新 offer 就回 answer，不再要求「重新整理頁面」。 |
| F6 | `ensureMedia()` → `pickUsbDevices()` | 先開一次 cam+mic 只為了讀裝置名稱，再關掉、再開一次。此 TV 外接鏡頭開啟約 1–2 秒，等於每次通話多等一倍。 | App 已事先授權，`enumerateDevices()` 直接就有名稱；把選定的 `deviceId` 記在 localStorage，只開一次。 |
| F7 | `bye` 處理 | 掛斷即關鏡頭，下次加入又要重開。 | 鏡頭保溫 60 秒再關。 |
| F8 | `call-join.html` `getUserMedia` | 沒給解析度，Safari 預設 640×480，放到電視整面是糊的（統計已證實：接收 640×480）。 | `width:{ideal:1280}, height:{ideal:720}`，由 WebRTC 自行降級。 |

### 1.2 穩定性與安全（伺服器）

| # | 位置 | 問題 | 建議 |
|---|------|------|------|
| S1 | `server.js /send` | 不驗 `from`，任何知道 roomId 的人都能冒充 `tv` 送 offer。多方之後 `from` 會決定路由，必須可信。 | 加入房間時發 **peer token**（`POST /join` → `{peerId, token}`），`/events`、`/send` 都帶 token；伺服器以 token 決定 `from`，不信客戶端填的值。 |
| S2 | `/register-code` | 任何人都能把某組配對碼改指到自己的房間（劫持配對碼）。 | 電視第一次註冊時取得 `tvSecret` 存 localStorage，之後註冊必須帶同一個 secret。 |
| S3 | `tooManyFailures()` | 速率限制是**全站**一個計數器，一個亂猜的人就能讓老婆也被擋。 | 改成每個 IP（Caddy 後面讀 `X-Forwarded-For`）。 |
| S4 | `queues` | 離線佇列無上限、無過期，手機換了 ID 之後舊佇列永遠留著。 | 每個 peer 佇列上限 200 筆、房間 10 分鐘無人連線就清掉。 |
| S5 | SSE `close` | 伺服器知道誰斷線，卻沒通知其他人；現在只能靠 WebRTC 逾時（10 秒以上）才發現。 | 伺服器維護房間成員表，SSE 斷開 → 廣播 `peer-left`。這是多方通話的基礎。 |
| S6 | `/rooms`、`codes`、`newCode()` | 舊的一次性配對碼流程已無人使用。 | 移除，減少混淆。 |
| S7 | 白名單 | `deviceId` 由手機自報，知道 UUID 就能冒充。家用可接受，但要寫明。 | 與 S1 的 token 綁定後自然解決（token 由伺服器簽發）。 |

### 1.3 UI / UX

| # | 位置 | 問題 | 建議 |
|---|------|------|------|
| U1 | 電視待命畫面 | 配對碼要用口頭或拍照傳，連結太長。 | **QR code**（內嵌 ~10 KB 的 qrcode 產生器，不依賴 CDN）；老婆用 LINE 相機掃 → 開 `/call-join?code=746394` 直接帶碼。 |
| U2 | 核准對話框 | 顯示「iPhone（ab12）想加入」，看不出是誰；多方時格子上也沒名字。 | 手機第一次用輸入一次名字（「媽媽」「阿姨」），之後電視顯示「媽媽 想加入」，格子標籤也用名字。 |
| U3 | 核准對話框 | 通話中有新裝置請求，整個畫面被蓋掉。 | 改成右上角小卡片（D-pad 可操作），15 秒沒回應自動拒絕；待命時才用大畫面。 |
| U4 | `switchMic` | 單鍵循環、看不到清單，也不知道哪個麥克風真的有聲音。 | **麥克風選單**：列出每個來源，右側即時音量條；用 `getStats()` 的 `audioLevel` 畫音量（不用 AudioContext，避開已知的 TV 音訊衝突）。使用者一眼看到哪個在動。 |
| U5 | `#msg` | 狀態文字混英文（`connected`、`connecting`）。 | 中文狀態膠囊：綠「連線良好」、黃「不穩（中繼）」、紅「重新連線中」。 |
| U6 | `#stats` | 統計列整場蓋在畫面上。 | 預設隱藏，遙控器「資訊」鍵或長按 OK 切換；10 秒後自動收起。 |
| U7 | 電視端 | 沒有掛斷鍵；按返回鍵直接離開 App，老婆那邊畫面定格到逾時。 | 返回鍵 → 「要掛斷嗎？」→ 送 `bye` 給所有人再退出（Android 端用 `evaluateJavascript` 呼叫）。 |
| U8 | 手機端 | 只有「加入／掛斷」。 | 加：靜音、關鏡頭、前後鏡頭切換（給小孩看東西用）、喇叭／聽筒。 |
| U9 | 手機端 iOS | 畫面會休眠；切到別的 App 鏡頭就停，回來不會自動恢復；聲音被擋時只有一行小字。 | `wakeLock`；`visibilitychange` 回前景自動重新取得鏡頭；聲音被擋時改成全螢幕大按鈕「點一下開始聽」。 |
| U10 | 手機端 | 斷線顯示「請重新整理頁面」。 | 自動重連：3 秒後自動重送 `hello`（電視會重新 offer），使用者不必動手。 |
| U11 | `MainActivity.java` | 沒開 `setWebContentsDebuggingEnabled`，出問題只能靠猜。 | 開啟後可用 `chrome://inspect` 透過 ADB 直接在電視頁面上執行 `getUserMedia` 實驗，麥克風調查會快很多。 |

---

## 2. 麥克風問題：目前證據與排查計畫

### 2.1 已確認的事實（唯讀查證，未中斷通話）

- 權限正常：`RECORD_AUDIO: allow`、`CAMERA: allow`；系統沒有任何靜音或限制（`mic mute … false`）。
- 兩個麥克風（內建 `Built-In Mic`、USB `Logitech StreamCam`）系統都認得、都「可用」。
- 只要網頁開始錄音，`AudioFlinger` 就以每秒約 100 次回報 `read failed: framesRead=0`（10 ms 一次讀取、每次 0 筆）；內建和 USB 都一樣。→ **問題在音訊 HAL 讀取層，不在網頁、不在 WebRTC**。
- 每段錄音剛好 **15 秒**就停（`duration=+15s`，多次一致）→ Chromium 的「零資料」看門狗把串流重啟，符合「完全沒資料」的判斷。
- HAL 另有錯誤：`mi_getCodecType: MI_AUDIO_GetHandle failed ret=9`（小米／MediaTek 音訊 HAL 拿不到控制代碼）。
- 系統屬性 `aaudio.mmap_policy=2`：HAL 走 AAudio MMAP 路徑；Chromium 用 16 kHz 單聲道開錄音，而 HAL 的待命輸入執行緒是 48 kHz 雙聲道。
- 電視沒有 root，`/proc/asound` 讀不到，無法直接用 `tinycap` 驗證 ALSA 層。

### 2.2 假設（依可能性排序）與對應測試

| 假設 | 測法（都在通話結束後做） |
|------|------|
| H1 取樣率／聲道不符：HAL 只在 48 kHz 雙聲道能出資料，16 kHz 單聲道回 0 | `tools/miccheck` 已建好，擴充成 **來源 × 取樣率（16k/44.1k/48k）× 聲道（1/2）** 全矩陣，每格錄 2 秒回報峰值。 |
| H2 錄音來源：`VOICE_COMMUNICATION` 路徑在此 HAL 壞掉，`MIC`／`UNPROCESSED`／`CAMCORDER` 可能正常 | 同上矩陣。 |
| H3 需要通話模式：有些 TV HAL 只在 `AudioManager.MODE_IN_COMMUNICATION` 才接通輸入路徑 | miccheck 加「切到通話模式再錄」一輪。 |
| H4 AAudio MMAP 輸入壞、傳統 `AudioRecord` 正常 | miccheck 本身就是傳統 `AudioRecord`；若 miccheck 有聲音而 WebView 沒有，就是這個，解法是在 App 端用原生錄音餵給網頁（WebView 無法直接換路徑，需走 `WebSocket`／`JavascriptInterface` 送 PCM，複雜度高）。 |
| H5 USB 麥克風需要 48 kHz 且 HAL 不重取樣 | H1 矩陣會一併回答。 |

### 2.3 若 HAL 真的修不好：繞路方案（本設計的主線）

把**客廳手機升格為「音訊端」**：手機同時負責收音與放音（喇叭開），電視喇叭靜音、只出畫面與鏡頭。

- 電視不再需要任何麥克風 → 完全繞過 HAL 問題。
- 回音消失：手機自己播出的聲音，手機的 AEC 有參考訊號可以消掉（現在的設計是電視放音、手機收音，兩邊都沒辦法消回音，這是結構性的）。
- 多方時客廳手機就是一個「只有聲音」的普通參加者，不必再經電視轉送（移除 `replaceTrack` 那一整段）。
- 代價：手機要放在電視旁、充電、螢幕常亮；聲音從手機喇叭出來，比電視喇叭小。可做成兩種模式切換：**「電視出聲」（現況）／「手機出聲」（建議預設）**。

---

## 3. 多方通話設計（電視＝主持人，其餘＝參加者）

### 3.1 拓樸與規模

- **全網狀（mesh）**：每兩個參加者之間一條 WebRTC 連線；電視也是參加者之一，不轉送任何媒體。
- 上限 **4 人**（電視＋3 支手機）。理由：mesh 下每個送出端要對每個對方各編一次，電視要同時編 3 路；搭配 H.264 硬體編碼與 540p 上限尚可，超過就要換 SFU（見 §6）。
- 「電視沒開就不能通話」：房間存活條件＝電視的 SSE 在線。電視離線時伺服器對任何 `hello` 回 `host-offline`，手機顯示「電視還沒開」。

### 3.2 身分與訊息

```
peerId   tv | 任意手機 p-<8碼>（伺服器核發）
token    伺服器核發，/events 與 /send 都要帶；伺服器用 token 決定 from，不信客戶端
```

| 訊息 | 方向 | 內容 |
|------|------|------|
| `hello` | 手機 → tv | `{name, deviceId, caps:{video,audio}}` |
| `approve` / `deny` | tv → 手機 | 核准結果（白名單命中則伺服器端直接放行，電視仍收到通知） |
| `roster` | 伺服器 → 新成員 | 目前房間成員 `[{peerId,name,caps}]` |
| `peer-joined` / `peer-left` | 伺服器 → 全員 | 成員異動（SSE 斷線由伺服器偵測後廣播） |
| `offer` / `answer` / `ice` | 點對點 | 帶 `to`；**規則：後加入者向每個既有成員發 offer**，杜絕 glare |
| `bye` | 任一 → 全員（`to:'*'`） | 主動離開 |
| `mute` | 任一 → 全員 | `{audio:bool, video:bool}`，讓格子顯示靜音圖示 |

伺服器新增：成員表、`to:'*'` 廣播、`peer-left`、token 核發；`/join-code` 回 `{roomId, peerId, token}`。

### 3.3 連線生命週期（每條 pc 各自管理）

```
new → connecting → connected
connected → disconnected ──(4 秒未恢復)──> restartIce()
                                   └─(仍 failed)──> 後加入者重新 offer
peer-left / bye → 關閉該 pc、移除格子，其餘不受影響
```

### 3.4 畫面配置

電視（1920×1080，遙控器操作）：

```
1 位遠端：全螢幕                      2 位：左右各半
┌────────────────────┐              ┌─────────┬─────────┐
│                    │              │  媽媽   │  阿姨   │
│       媽媽          │              │         │         │
│              [本機] │              │         │   [本機]│
└────────────────────┘              └─────────┴─────────┘
3 位：上二下一（或 2×2 留一格給本機）
```

- 每格左下：名字＋麥克風狀態；說話中格子亮綠框（`audioLevel` 統計，不用 AudioContext）。
- 底部工具列自動隱藏，D-pad 向下叫出：參加者／麥克風來源／音訊模式／統計／掛斷。
- 右上角小卡片處理加入請求（§1.3 U3）。

手機：

- 預設「說話者放大」：正在說話的人全螢幕，其餘縮成下方長條；點任一格可釘選。
- 底部：靜音、鏡頭開關、翻轉、喇叭、掛斷；頂部狀態膠囊。

### 3.5 音訊

- 每位參加者直接播放其他所有人的聲音（mesh 天生如此）。
- 「手機出聲」模式下：電視的 transceiver 設為 `video: sendrecv、audio: inactive`，客廳手機 `audio: sendrecv、video: inactive`。
- 「電視出聲」模式下維持現況（電視收所有人音訊並播放；若電視麥克風修好則電視也送音）。

### 3.6 核准與安全

- 白名單仍以裝置為單位，但改由伺服器簽 token（§1.2 S1），電視核准後伺服器才發 token；手機沒有 token 就連不上 `/events`。
- 電視可在參加者清單中「移除」某人（送 `kick`，伺服器撤銷 token）。
- 配對碼註冊綁 `tvSecret`（S2）。

---

## 4. 實作分期（每期都可獨立上線，先後不互相卡）

| 期 | 內容 | 預估 | 風險 |
|----|------|------|------|
| **P0 零風險修補＋麥克風排查** | F1 H.264 偏好、F2 位元率上限、F3 送出端統計、F4 await ICE、F5 restartIce、F6 鏡頭只開一次、F7 鏡頭保溫、F8 手機 720p、U5 中文狀態、U7 返回鍵掛斷、U9 wakeLock／前景恢復、U10 自動重連、U11 WebView 除錯開關；**miccheck 全矩陣（來源×取樣率×聲道×通話模式）** | 半天 | 低；不改協定，現有流程全相容 |
| **P1 伺服器** | S1 token、S2 tvSecret、S3 每 IP 限流、S4 佇列上限、S5 成員表＋`peer-left`＋廣播、S6 清舊碼 | 半天 | 中；需同步改三個頁面的連線方式 |
| **P2 電視多方** | mesh 連線管理、格子排版、名字、說話偵測、小卡片核准、底部工具列、返回鍵掛斷 | 1 天 | 中；電視效能需實測 2～3 路 |
| **P3 手機多方** | mesh、說話者放大、工具列、名字輸入、QR 掃碼帶碼 | 1 天 | 低 |
| **P4 音訊端模式** | 「手機出聲」切換、電視 audio inactive、客廳手機升格參加者、移除 `mic` 轉送 | 半天 | 低；且能一併解回音 |
| **P5 麥克風排查** | miccheck 全矩陣、通話模式測試、必要時原生錄音餵網頁 | 視結果 | 高（HAL 問題不一定修得好；P4 是保底） |

建議順序：**P0 → P1 → P4 → P2 → P3 → P5**。P4 提前是因為它用手機解掉「電視沒聲音」和「回音」兩個最痛的問題，不必等 P5 的結果。

---

## 5. 部署與驗證（通話結束後才做）

1. 本機 `node --check` 三個檔案 → `scp` → `systemctl restart tv-mirror`（約 2 秒中斷）→ 電視 App 重開。
2. 驗證清單：
   - 外網（老婆用 4G）能接通，統計列路徑顯示 `relay` 或 `srflx`。
   - 拔掉電視網路線 5 秒再插回：畫面在 10 秒內自動恢復，不需任何操作。
   - 電視送出端統計 `qualityLimitationReason` 應為 `none`，fps ≥ 20。
   - 三支手機同時加入：電視 CPU 不過熱、每格 ≥ 15 fps。
   - 「手機出聲」模式：老婆說「測試」時，自己不會聽到回音。

---

## 6. 之後再評估：SFU

人數要超過 4、或電視撐不住 3 路編碼時，改用 SFU（LiveKit 或 mediasoup）放在同一台 VPS：每人只上傳一路、下載 N 路，電視只需編一次。VPS 2 核 3.3 GB 跑 4～6 人的 SFU 足夠（不轉碼），但要再開一段 UDP 埠並評估與現有 coturn 的共存。本設計的信令抽象（peerId／token／roster）可直接沿用，屆時只換媒體層。

---

## 附錄 A：本次稽核時的環境數據

- 電視：MiTV-MSOR0，Android 11，2.9 GB RAM（可用約 1 GB），WebView Chromium 156。
- 視訊編解碼：`OMX.MS.AVC.Encoder`（H.264 硬體編碼）、`OMX.MS.AVC.Decoder`、`c2.android.avc.*`。
- 上次通話統計（區網內）：接收 640×480 30 fps、延遲 15 ms、路徑 `host`。
- VPS：Ubuntu 26.04，2 核 3.3 GB，Caddy 2.6.2、coturn 4.6.1、Node 26；`tv-mirror` 服務埠 8790。

---

## 附錄 B：2026-11-07 麥克風排查（P0 期間，自主測試紀錄）

### B.1 根因（已確認）

1. **螢幕保護程式搶走焦點 → 系統拒絕錄音。** 系統設定 `screensaver_enabled=1`，保護程式元件是 `com.gathin.tvhome`（舊的 CleanYourTV 桌面）的 `DreamActivity`。保護程式一啟動，前景就不是我們的 App，AudioPolicy 立刻拒絕所有錄音：
   `AudioPolicyIntefaceImpl: getInputForAttr permission denied: recording not allowed for uid …`
   本次診斷中，所有 32 次 `setPreferredDevice` 失敗都集中在保護程式啟動之後（21:18），之前的測試正常。
   → **修正：** 電視 App 通話時必須保持前景，並停用保護程式（見 B.4，已暫時套用，待 Kevin 確認是否保留）。

2. **WebView 的 getUserMedia 在保護程式下失敗：** `NotReadableError: Could not start audio source`。與 1 同源。

3. **內建麥克風 16 kHz 讀不到、48 kHz 有聲音**（前一輪結果：`MIC | 48000 → ac=444 peak=1013`；`MIC | 16000 → ac=0`）。這是 H1（取樣率）的初步證據，需要在前景狀態下重新確認。

4. **舊的「有聲音」判定是假的。** v1 用峰值判定，固定的直流偏移（約 1004）被誤判成有聲音。v2 改用去除直流後的 AC 音量。

5. **USB 麥克風（Logitech StreamCam）：** v1（前景、正常）時 16/48 kHz、MIC/VOICE_COMM/UNPROCESSED/CAMCORDER 全部有真實訊號（峰值 3000～26000）。這表示 **USB 麥克風在原生 AudioRecord 下可用**。通話中讀到 0 筆資料（前述「read failed: framesRead=0」）很可能也是因為通話當時被保護程式或焦點問題影響，需要在前景通話中重測確認。

### B.2 待確認

- 前景＋保護程式停用的條件下，WebView getUserMedia 是否能拿到 USB 與內建麥克風的真實訊號（v3 測試）。
- 若 WebView 仍失敗而原生 AudioRecord 成功 → 問題在 Chromium 音訊路徑，需要改 WebView 旗標（`/data/local/tmp/webview-command-line`，debug 版可用）或改原生餵音訊。

### B.3 電視狀態

v3 測試中途，電視網路與 ADB 離線（ping 無回應、5555 埠關閉），疑似進入待機。需要 Kevin 用遙控器喚醒後再續測。

### B.4 系統設定變更（需 Kevin 確認是否保留）

| 設定 | 原值 | 目前 | 還原指令 |
|---|---|---|---|
| `secure screensaver_enabled` | 1 | 0 | `adb shell settings put secure screensaver_enabled 1` |
| `secure screensaver_activate_on_sleep` | 1 | 0 | `adb shell settings put secure screensaver_activate_on_sleep 1` |

建議保留（停用保護程式）：通話是持續型應用，保護程式啟動會打斷錄音與畫面。

### B.5 交接（2026-11-07，等待電視上線）

阻塞點：電視網路與 ADB 離線，無法執行 v3 前景測試。已做的靜態稽核：`call-tv.html` 與 `call-join.html` 所有被呼叫的函式都有定義，沒有發現未定義引用。

待辦（電視上線後依序執行）：
1. 喚醒電視，確認 `adb connect <TV_IP>:5555` 成功。
2. 重跑 `tools/miccheck`（前景、保護程式已停用），取得 WebView 的 `WEBMIC` 結果，判斷 USB 與內建麥克風是否有真實訊號。
3. 若 WebView 失敗而原生 AudioRecord 成功 → 評估 WebView 旗標或原生餵音訊。
4. 還原 `tvcall` App 到前景，請 Kevin 實際通話一次，檢查統計列的 H.264、送出端頻寬狀態、斷線自動恢復。
5. 決定保留或還原保護程式設定（見 B.4），並移除 `tw.kevin.miccheck`。

### B.6 不需電視的自測（2026-11-07）

- 瀏覽器實測正式網址：`call-join`、`mic`、`call-tv` 三頁皆能載入，標題與初始狀態文字正確，`common.js` 的共用函式（Signal、preferH264、watchConnection）皆存在。
- 尚未涵蓋：真實 WebRTC 協商、鏡頭與麥克風實際擷取、電視端 H.264 硬體編碼、斷線 ICE 重啟。這些必須在電視上線後測。

### B.7 活體 WebRTC 自測（無電視，兩個瀏覽器分頁＋假鏡頭／假麥克風）

方法：ego-browser 開兩個分頁（電視頁、手機頁），各自用 canvas＋振盪器取代鏡頭與麥克風，走正式網址的真實配對、核准、協商流程。

通過：
- 配對碼加入、電視核准（允許一次）→ 雙方 `connectionState=connected`。
- 雙向視訊皆為 **H.264**、**24 fps**、解析度 640×360、`qualityLimitationReason=none`，位元組雙向增加。 → P0 的編碼器偏好在 Chromium 上生效（電視硬體編碼仍待實機確認）。
- 手機按掛斷後（連線正常時），電視收到 `bye` 並回到待命，顯示「對方已掛斷」。

發現的問題（待修）：
1. **自動重新加入不穩**：手機 pc 進入 `failed` 後會每 3 秒送一次 `hello`，但電視端在某些時序下沒有顯示核准視窗（approveBox 的 class 正常，但未出現在畫面上）。需加上 TV 端的 hello 節流與「已在通話中收到新 hello」的明確處理（顯示小卡片，而不是依賴覆蓋層狀態）。
2. **掛斷訊息時序**：當手機處於 `重新加入中…` 狀態時按掛斷，電視一度仍顯示「連線良好」。手機端 `endCall` 已正確送出 `bye`，疑似是電視先前已因 `failed` 而收到的舊 `hello` 造成狀態覆寫。需在電視端把 `bye` 之後的 `hello` 視為新請求，且 `teardownCall` 要清除 pending。
3. 測試過程中有一次 `allowOnce` 按鈕因覆蓋層隱藏而無法點擊（zero-sized），表示核准 UI 與覆蓋層的顯示狀態沒有統一管理。建議把核准卡片移到覆蓋層之外的固定層。

結論：WebRTC 協商、H.264、雙向傳輸、基本掛斷是 OK 的；**自動重新加入與核准 UI 的狀態管理是現在最需要修的 bug**。

### B.8 修正與複測（2026-11-07）

已修正（已部署靜態頁，伺服器不需重啟）：
- **Bug 3 核准 UI**：核准卡片改為獨立固定層 `.card`，不再放在覆蓋層裡。複測：手機重新加入時，電視即使在通話中也會跳出核准卡片（通過）。
- **Bug 2 掛斷時序**：電視收到新的 `hello` 時先關閉舊的 pc 並清畫面；`bye` 會同時隱藏核准卡片。複測：正常連線後掛斷，電視回到待命（通過）。
- **Bug 1 重新加入**：手機端 `scheduleRejoin` 加上重複排程保護，`endCall` 清除計時器；`tapSound`（點一下開始聽）改為只覆蓋影像區，避免蓋住掛斷按鈕（已修，複測時發現並修正）。

複測結果：
- 首次連線 → 雙向 H.264 24 fps（通過，見 B.7）。
- 手機重新整理後重新加入 → 電視顯示核准卡片（通過）。
- 重新加入後核准 → ICE 時好時壞：有一輪電視停在 `checking`、手機 `connected`；另一輪首次連線同樣停在 `connecting` 超過 60 秒。直接送 `bye` 至電視房間則可正確回到待命（通過）。
- 懷疑 ICE 卡住與 TURN（coturn 中繼候選）或測試瀏覽器環境的網路有關，尚未確認；需在電視實機與外網手機上重測，並比較統計列的路徑是 `直連` 還是 `中繼`。

未完成：電視硬體 H.264、真實鏡頭與麥克風、前景麥克風測試（v3）仍需電視上線。

### B.9 安全事件：TURN 伺服器為 open relay（2026-11-07，已處置、未修復）

發現：自測 ICE 卡住時，以最小 TURN 客戶端直接對 `turn.<YOUR_DOMAIN>:3478` 送出**未認證**的 Allocate 請求，coturn 回傳成功（0x0103，含 XOR-RELAYED-ADDRESS）。這代表任何人都能把 VPS 當成 UDP 中繼使用。

處置：
1. 立即 `systemctl stop coturn`，coturn 目前保持停止。
2. 備份原設定：`/root/backups/2026-11-06/turnserver.conf.bak-open-relay-*`。
3. 在 `use-auth-secret` 後加入 `lt-cred-mech`，重啟後**仍然接受匿名配置**，未修好。
4. 輪替 TURN 共用密鑰（舊密鑰曾在對話紀錄中顯示，視為外洩）。新值只寫入 `/etc/turnserver.conf` 與 `/etc/tv-mirror/turn.secret`（權限 600）。
5. 重啟 `tv-mirror`（電視離線，無進行中通話）。

目前影響：
- TURN 中繼不可用，ICE 只能使用直連與 STUN 候選；跨 NAT 的外網連線可能失敗或變慢。
- 原因：coturn 4.6.1 的 `use-auth-secret` 設定沒有擋下匿名請求，原因尚未找到。

待修（必須在重新啟用 coturn 之前完成）：
- 找出為何匿名請求被接受（建議先在前景開啟詳細日誌，並用上面的兩段式測試當回歸測試：匿名應為 0x0113，帶臨時帳密應為 0x0103）。
- 若無法在短期內修好，改用 `static user=` 固定帳號（每位家人一組）或暫不啟用 TURN，並在防火牆限制只允許 3478 與 49160–49200 的必要來源。
- 修好後把「匿名 Allocate 必須被拒絕」加入 `deploy.sh` 的檢查。

### B.10 TURN open-relay 排查進度（2026-11-07，未完成）

- 隔離實驗（只在 VPS 的 127.0.0.1 測試，未開放防火牆）：
  - 靜態帳號模式（`user=` + `lt-cred-mech`）：匿名 Allocate 回 **0x0113（401）** ✓
  - REST 模式（`use-auth-secret` + `lt-cred-mech`）：匿名 Allocate 回 **0x0113（401）** ✓
  - 加上正式環境獨有選項（fingerprint、stale-nonce、total-quota）的仿正式設定：匿名 Allocate 回 **0x0113（401）** ✓
- 矛盾點：先前由 Mac 經**公網 IP** 對正式 3478 測試時，匿名 Allocate 回 **0x0103（成功）**。本機回環的所有仿正式測試都正確拒絕。因此問題可能只在公網路徑或正式環境的某個未知差異，尚未確認。
- 未完成：要重現公網行為，需要從外部對測試埠發送匿名請求。嘗試臨時開放 ufw 測試埠時，該操作被安全分類器阻擋，未執行。
- 目前狀態：正式 `coturn` 維持停止；`tv-mirror` 運作中；無殘留的測試程序或防火牆規則。
- 下一步（需要 Kevin 決定）：
  1. 授權一條臨時防火牆規則（例如只允許 Kevin 的 Mac 公網 IP 存取測試埠 3481/udp），完成公網重現後立刻刪除；或
  2. 在 Kevin 的環境直接從外部網路執行測試腳本（見附錄 B.9 的兩段式測試邏輯）。
- 在確認之前，不要重新啟用正式 coturn。

### B.11 ICE 卡住的根因證據（2026-11-07）

實驗：coturn 停止（TURN 不可用）時，雙分頁活體測試連續 3 次首次連線。
結果：三次皆在約 1.0 秒內 `connected`，雙向 H.264。

對照：coturn 運作時，電視端的 ICE 停在 `checking`，本地候選只有 host 與 srflx、沒有 relay；手機端 ICE 已 connected 但整體仍 connecting。

結論：ICE 卡住與 coturn 運作有關，不是信令或手機端流程的問題。coturn 運作時，瀏覽器會嘗試 TURN 配置與權限，但實際中繼無法完成，ICE 因此等到逾時。TURN 停止後，瀏覽器快速放棄中繼候選，直連立即成功。

影響與待辦：
- 目前（TURN 停止）：區網與可直連的網路可正常通話；需要 TURN 的網路（對稱型 NAT、部分行動網路）暫時無法通話。
- TURN 修復後，必須以本節的 3 次連線時間作為回歸測試：首次連線應在數秒內 connected，不應停在 checking。
- 「coturn 配置仍是 open relay」與「中繼無法完成」兩個現象可能同源（coturn 配置／權限設定問題），需一併處理（見 B.9、B.10）。

### B.12 TURN 公網重現與驗證（2026-11-08，已還原）

方法：在主機以正式設定的複本（埠 3481、獨立密鑰、加上 `lt-cred-mech`）啟動測試實例；防火牆只臨時允許 Kevin 的 Mac 公網 IP 連 3481/udp；測完立即刪除規則並停止實例、刪除暫存檔。

結果：
- 公網匿名 Allocate → **0x0113（401，要求認證）** ✓ 不是 open relay。
- 帶伺服器發出的臨時帳密 → **0x0103（中繼配置成功）** ✓ TURN 功能正常。
- 先前看到的 0x0103 匿名成功結果，發生在更早的設定（未加 `lt-cred-mech`、舊密鑰）；目前設定已通過公網驗證。

狀態：
- 正式 coturn 仍為停止（我沒有改動正式服務）。
- 防火牆：已還原，3481 規則不存在。
- 下一步（需要 Kevin 決定）：是否用目前已驗證的設定重新啟用正式 coturn，之後用雙分頁測試做 ICE 回歸（首次連線應在數秒內 connected）。

電視端的麥克風前景測試（v3）因為電視要給小孩看 YouTube，暫緩到之後。

### B.13 UI 改進（2026-11-08，依大眾趨勢研究落實 1–5 項）

已落實（靜態頁已部署，電視 App 尚未重開載入）：
1. **加入流程**：名字只問一次（記住後隱藏）；權限被拒時顯示分平台步驟（iPhone / Android / 找不到裝置三種文案）；聲音被擋時沿用全螢幕「點一下開始聽」。
2. **加入請求卡片**：預設焦點在「拒絕」；顯示來者名字；30 秒倒數後自動拒絕；按鈕順序為拒絕、只允許這次、允許並記住。
3. **狀態文案**：三段式大字（畫面順暢／網路不穩，畫面可能變模糊／連線中斷，正在重連）；統計數字預設隱藏；狀態列加 `role=status`、`aria-live`。
4. **控制列**：手機通話中有靜音、關閉鏡頭、掛斷三鍵，按鈕最小 52px；掛斷為紅色且有文字；掛斷後按鈕改為「重新加入視訊」（誤掛復原）。
5. **QR 配對**：電視待命畫面顯示 QR code（內容為 `call-join?code=…`）與 6 位數配對碼。QR 函式庫由 cdnjs 載入（qrcode-generator 1.4.4），載入失敗時只顯示文字。

瀏覽器測試（無電視）：
- QR 正確渲染；名字輸入框在無名字時顯示、記住後隱藏。
- 核准卡片預設焦點為「拒絕」，顯示「媽媽（裝置） 想加入視訊」與倒數。
- 允許後狀態為「畫面順暢」，統計隱藏；靜音與關鏡頭按鈕文字即時切換；掛斷後電視顯示「對方已掛斷」，手機按鈕改為「重新加入視訊」。
- 模擬權限被拒，顯示分平台的開啟步驟。

未完成（需實機）：
- 電視上的實際版面與 3 公尺可讀性；QR 在 TCL WebView 的載入；iPhone Safari 的實際權限流程與聲音播放。
- 字級跟隨系統（第 6 項無障礙）未做，屬於下一階段。
- 電視 App 需重開一次才會載入新版頁面（等小孩看完 YouTube 再做）。

### B.14 手機端稽核與修正（2026-11-08）

稽核來源：程式稽核 agent（唯讀）、手機尺寸實測 agent、平台限制研究 agent。

已修（已部署靜態頁，瀏覽器冒煙測試通過）：
- 首屏：遠端影像區縮為 42dvh，加入按鈕完整可見（375×812 實測底部 629px）；錯誤訊息移到按鈕上方（實測 539–561px）。
- S1 靜音／關鏡頭：選擇存為狀態變數，按鈕文字不依賴串流是否存在，重新加入或回前景時套用到新 track。
- S2 掛斷按鈕：紅色（實測 rgb(255,59,48)），與其他鍵間距拉開。
- S3 被拒：走 endCall 清理（停止鏡頭與麥克風、關閉連線）；拒絕後不再接受新 offer。
- S6 等待文案：「請到電視上按『允許』，家人才看得到你」，30 秒後提示找人看電視。
- S7 改名字：名字記住後顯示「改名字」入口。
- M 項：meta 補上 mobile-web-app-capable。

尚未修（依優先順序，待決定）：
- S4 WakeLock 被系統釋放後不會重新取得（需實機）；S5 客廳麥克風頁沒有回前景與 track ended 處理。
- M5 ontrack 重複指定 srcObject、M6 handleOffer 競態與 ICE 候選遺失、M11 掛斷後 SSE 不關閉、M12 重新加入沒有上限。
- M7 錯誤訊息仍有英文或技術名稱（例如 NotAllowedError）。
- M19 無障礙（label、aria-pressed、焦點管理）、M20 字級不跟隨系統、M3 白字按鈕對比不足（估算約 2.2:1，需以工具精算）。
- 所有實機項目（iOS 背景行為、wake lock、手勢時效、安全區）待 iPhone 實測。

### B.15 手機端第 2、3、4 項修正（2026-11-08）

第 2 項 連線韌性與競態（已修）：
- M6：信令依序處理（佇列），ICE 候選在 remoteDescription 設定前暫存後再加入。
- M5：串流只設定一次；只有 `NotAllowedError` 才顯示「點一下開始聽」。
- M12：重新加入最多 5 次，退避 3、6、12、24、48 秒；超過後結束並提示。
- M14：收到信令連線開啟事件後才送 hello。
- M11：掛斷與停止時關閉 SSE（`Signal.close()`），不再無限重連。
- M13：送出失敗會顯示「網路不通，正在重試」。
- M9：非 https 時直接提示，不再誤判為權限問題。
- S4：常亮鎖被系統釋放後清空，回前景時重新請求（麥克風頁同樣處理）。
- S5：麥克風頁回前景時檢查軌道，已中斷就明確告知。
- M15：麥克風頁在點擊當下建立音訊環境，避免手勢過期。
- M22：麥克風頁被拒絕時顯示原因並停止擷取。

第 3 項 錯誤訊息（已修）：
- M7：網路錯誤、權限被拒、找不到裝置、被其他 App 占用，各有中文說明與下一步；不再顯示 `NotAllowedError` 這類名稱。

第 4 項 無障礙與對比（已修，部分）：
- M19：名字與配對碼輸入框有 label；靜音與關鏡頭按鈕有 `aria-pressed`；「點一下開始聽」是可聚焦按鈕，支援 Enter 與空白鍵；權限說明區域 `role=status`、`aria-live`；麥克風頁的狀態列有 `aria-live`。
- M3：按鈕底色加深（綠 #1f7a3a、橘 #8a5a00），白字對比提升（估算達 4.5:1 以上，需工具精算）。
- 未做：M20 字級跟隨系統（需改用 rem 並實機確認 iOS 行為）。

回歸測試（雙分頁，無電視）：
- 名字已記住 → 配對碼輸入 → 加入 → 核准卡片預設焦點「拒絕」→ 允許 → 雙方 `connected`（畫面順暢）→ 手機掛斷 → 電視顯示「對方已掛斷」→ 手機按鈕變「重新加入視訊」。

未驗證（需實機）：iOS 背景與常亮的實際行為、手勢時效對播放與音訊環境的影響、權限被拒的實際提示文字（各瀏覽器版本不同）。

### B.16 電視端麥克風軌道修復（2026-11-08）

根因（已驗證）：電視的 WebView 麥克風在通話中拿不到資料（AudioFlinger `read failed: framesRead=0`），Chromium 約 15 秒後結束音軌；通話中 `outbound-rtp` 音訊位元組為 0，手機端收到的是靜音。原生 AudioRecord 錄 USB 麥克風則正常（前景測試峰值 3000～32000）。

修復：
- 原生橋接 `NativeMic`（tw.kevin.tvcall）：用 AudioRecord 錄 USB 麥克風（找不到才用內建），48kHz 單聲道，每 100 毫秒把 PCM 送進網頁。
- 網頁端用 `MediaStreamTrackGenerator` + `AudioData` 把 PCM 變成音軌（電視 WebView 支援，已確認）。鏡頭仍由 WebView 取得。
- 麥克風軌道結束時，改為重新接上原生橋接；失敗則退回 WebView 麥克風。
- 重新加入時，若只有麥克風壞掉，只換麥克風、不動鏡頭（修正前會沿用壞掉的音軌）。

驗證：
- 電視端（不需通話）：原生橋接產生 6000 毫秒音訊，音軌 live，RMS 0.02、峰值 0.96。
- 端到端（瀏覽器手機加入通話）：電視顯示「麥克風：USB 麥克風（原生）」，手機端收到電視的聲音 RMS 0.004～0.02、峰值 0.03～0.14，會隨環境變化（修復前為 0）。
- 尚未確認：實際人聲的清晰度與音量（需要有人在電視旁說話、手機端確認）。

限制：
- AudioRecord 只在 App 前景時能錄音（螢幕保護程式需停用，已停用）。
- 視訊仍為 VP8，未確認 H.264（見 B.12 之後的待辦）。

### B.17 TURN open relay 根因與修復（2026-11-08）

根因（已驗證）：`/etc/turnserver.conf` 權限為 600（root:root），coturn 以 `turnserver` 使用者執行，讀不到設定，於是回退到預設值（不需認證）→ open relay。先前在測試埠以 root 執行時結果正確，因此誤以為設定沒問題。

修復：
- `/etc/turnserver.conf` 改為 root:turnserver 640（服務可讀，其他人不可讀）。
- 建立 `/var/log/turnserver.log`，擁有者 turnserver。
- 正式 coturn 已啟動。

驗證（公網，從 Kevin 的 Mac）：
- 匿名 Allocate → 0x0113（401）✓
- 帶 `/turn` 發出的臨時帳密 → 0x0103（中繼配置成功）✓

影響：外網（老婆在外）的 ICE 現在可以使用 TURN 中繼。

待辦：
- 部署流程加入「匿名 Allocate 必須為 0x0113」的檢查（deploy.sh）。
- 老婆在外網重新加入一次，確認畫面與聲音。
