# tv-mirror（客廳視訊通話）

家用的視訊通話系統：**電視**接 USB 鏡頭與麥克風當客廳端，家人用**手機網頁**加入通話；另可用手機當「客廳麥克風」。不需要安裝 App，手機只要瀏覽器。

- 電視端：Android TV 上的 WebView App（`android/`），載入伺服器上的 `/call-tv`。
- 手機端：`/call-join`（加入通話）、`/mic`（客廳麥克風）。
- 伺服器：`server.js`，Node.js 內建模組，無外部相依。負責配對、信令（SSE + POST）、TURN 臨時憑證。
- 媒體走 WebRTC（H.264 優先），外網需要 TURN（coturn）。

給 AI 或新進開發者：先讀 [`AGENTS.md`](AGENTS.md)（架構、協定、踩過的坑、禁止事項）。

## 目錄

| 路徑 | 內容 |
|---|---|
| `server.js` | 信令與配對伺服器（HTTP / SSE，埠預設 8787） |
| `public/common.js` | 共用：SSE 信令、TURN 取得、H.264 偏好、連線看門狗、統計 |
| `public/call-tv.html` | 電視端頁面（待命、核准卡片、通話、麥克風切換） |
| `public/call-join.html` | 手機加入頁（名字、配對碼、靜音／關鏡頭／掛斷） |
| `public/mic.html` | 客廳麥克風頁（手機當麥克風，音量條） |
| `android/` | 電視 App（WebView 外殼＋原生麥克風橋接 `NativeMic`） |
| `tools/miccheck/` | 電視麥克風診斷 App（逐一測試每個輸入裝置與錄音來源） |
| `deploy.sh` | 一鍵部署（需 `deploy.env`，範例見 `deploy.env.example`） |
| `docs/DESIGN.md` | 設計稿與完整排查紀錄（附錄 B） |

## 本機開發

```bash
node server.js                 # http://localhost:8787 （沒有 TURN_SECRET 時 /turn 回 503，區網仍可用）
# 電視頁：http://<你的電腦IP>:8787/call-tv   手機頁：http://<你的電腦IP>:8787/call-join
```
手機的鏡頭需要 https，區網測試可用自簽憑證（把 `cert.pem`、`key.pem` 放進 `certs/`，不要 commit）。

## 正式部署（伺服器）

1. 準備一台 Linux 主機，安裝 Node.js、coturn、反向代理（Caddy 或 nginx 皆可，負責 HTTPS）。
2. 環境變數：`PORT`、`BEHIND_PROXY=1`、`TURN_SECRET`（與 coturn 的 `static-auth-secret` 相同）、`TURN_HOST`、`TURN_PORT`。
3. coturn 必須設定 `use-auth-secret`＋`lt-cred-mech`，且 **設定檔對 coturn 執行帳號可讀**（否則會退回預設、變成 open relay，見 `docs/DESIGN.md` B.17）。
4. 部署前後都要驗證：匿名 Allocate 應回 0x0113（401），帶臨時帳密應回 0x0103。
5. 複製 `deploy.env.example` 為 `deploy.env` 並填寫，然後 `./deploy.sh`（或 `--web-only` 只上傳網頁，不重啟、不碰電視）。

## 電視 App

```bash
cd android && ./gradlew assembleRelease   # 正式簽章：見 app/build.gradle 的註解（金鑰不進 git）
adb install -r app/build/outputs/apk/release/app-release.apk
```
第一次開啟會要求輸入伺服器網址（需 https）；之後可按遙控器「選單」鍵修改。電視需要：USB 鏡頭（可選）、網路、開啟開發者選項的網路偵錯（ADB，僅用於維護）。

## 使用流程

1. 電視開啟後顯示 6 位數配對碼與 QR code。
2. 家人在手機開啟加入頁（或掃 QR），輸入名字（只要一次）與配對碼，按「加入視訊」。
3. 電視跳出核准卡片（預設焦點在「拒絕」，30 秒無人處理自動拒絕）。按「允許並記住」後，同一台手機之後不需再確認。
4. 通話中可靜音、關鏡頭、掛斷；掛斷後按「重新加入視訊」即可再接。

## 授權與安全

- 這是家用專案，不是公開服務。請自行架設伺服器與 TURN，不要使用別人的網址。
- 不要把 `deploy.env`、`certs/`、`data/`、TURN 密鑰、簽章金鑰提交進 git（已在 `.gitignore`）。
- 如果 TURN 密鑰外洩，請立刻輪替，並重新驗證匿名請求被拒絕。
