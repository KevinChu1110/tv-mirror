package tw.kevin.tvcall;

import android.Manifest;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

// 電視視訊端：全螢幕 WebView 載入通話頁面。
// 相機／麥克風權限由 App 自己處理，避開 TCL 內建瀏覽器的權限對話框 bug。
public class MainActivity extends Activity {

    private static final String PREFS = "tvcall";
    private static final String KEY_URL = "server_url";
    private static final int REQ_MEDIA = 1001;

    private WebView web;
    private PermissionRequest pendingRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        // 允許用 chrome://inspect 透過 ADB 直接除錯電視上的頁面（麥克風實驗用）
        // 只有 debug 版開啟遠端除錯，正式版不開（避免被 ADB 檢查頁面內容）
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // 電視端不需要點擊就能播聲音

        web.addJavascriptInterface(new NativeMic(), "NativeMic");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // 只允許網頁要求的相機與麥克風
                pendingRequest = request;
                boolean needCam = false, needMic = false;
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) needCam = true;
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) needMic = true;
                }
                if (hasAll(needCam, needMic)) {
                    grantPending();
                } else {
                    requestPermissions(new String[]{
                            Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQ_MEDIA);
                }
            }
        });

        requestPermissions(new String[]{
                Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQ_MEDIA);
        String url = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, null);
        if (url == null) showServerDialog(); else web.loadUrl(url + "/call-tv");
    }

    // 伺服器網址：第一次開啟時詢問，之後可按遙控器「選單」鍵修改
    private void showServerDialog() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint("https://你的網域");
        String current = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, "");
        input.setText(current);
        new AlertDialog.Builder(this)
                .setTitle("輸入伺服器網址（需為 https）")
                .setView(input)
                .setCancelable(current != null && !current.isEmpty())
                .setPositiveButton("儲存", (d, w) -> {
                    String u = input.getText().toString().trim();
                    while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
                    if (!u.startsWith("https://")) { showServerDialog(); return; }
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, u).apply();
                    web.loadUrl(u + "/call-tv");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean hasAll(boolean cam, boolean mic) {
        boolean okCam = !cam || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean okMic = !mic || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        return okCam && okMic;
    }

    private void grantPending() {
        if (pendingRequest == null) return;
        pendingRequest.grant(pendingRequest.getResources());
        pendingRequest = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_MEDIA) return;
        if (pendingRequest != null) {
            boolean ok = true;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
            if (ok) grantPending();
            else { pendingRequest.deny(); pendingRequest = null; }
        }
    }

    // 遙控器返回鍵：先問要不要掛斷，確定後通知對方再離開，避免老婆那邊畫面定格到逾時
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) { showServerDialog(); return true; }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            new AlertDialog.Builder(this)
                    .setTitle("要掛斷並離開嗎？")
                    .setPositiveButton("掛斷", (d, w) -> hangupAndFinish())
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void hangupAndFinish() {
        web.evaluateJavascript("typeof tvHangup === 'function' ? tvHangup() : ''", null);
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 600);
    }

    // 原生麥克風橋接：WebView 的麥克風在這台電視上拿不到資料，但原生 AudioRecord 可以。
    // 原生端錄 48kHz 單聲道，每 100 毫秒把 PCM 送進網頁（window.nativePcm），網頁再變成音軌。
    private final class NativeMic {
        private static final int RATE = 48000;
        private static final int CHUNK = RATE / 10;      // 100 ms
        private volatile AudioRecord rec;
        private volatile Thread thread;

        @JavascriptInterface
        public synchronized boolean start() {
            if (rec != null) return true;
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            AudioDeviceInfo pick = null;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (d.getType() == AudioDeviceInfo.TYPE_USB_DEVICE) { pick = d; break; }
            }
            if (pick == null) for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) { pick = d; break; }
            }
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, CHUNK * 4));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) { r.release(); return false; }
            if (pick != null) r.setPreferredDevice(pick);
            r.startRecording();
            rec = r;
            final AudioRecord rr = r;
            thread = new Thread(() -> {
                short[] buf = new short[CHUNK];
                while (rec == rr) {
                    int n = rr.read(buf, 0, buf.length);
                    if (n <= 0) continue;
                    byte[] bytes = new byte[n * 2];
                    for (int i = 0; i < n; i++) { bytes[2*i] = (byte) (buf[i] & 0xff); bytes[2*i+1] = (byte) ((buf[i] >> 8) & 0xff); }
                    String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    runOnUiThread(() -> { if (web != null) web.evaluateJavascript("window.nativePcm && window.nativePcm('" + b64 + "', " + RATE + ")", null); });
                }
            }, "native-mic");
            thread.start();
            return true;
        }

        @JavascriptInterface
        public synchronized void stop() {
            AudioRecord r = rec;
            rec = null;
            if (r != null) { try { r.stop(); } catch (Exception ignored) {} r.release(); }
        }

        @JavascriptInterface
        public boolean isRunning() { return rec != null; }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
