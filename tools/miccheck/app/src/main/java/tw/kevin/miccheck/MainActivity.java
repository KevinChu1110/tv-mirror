package tw.kevin.miccheck;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.List;

// 麥克風診斷 v2。兩段測試，結果都寫進 logcat（tag MicCheck）：
//  A. 原生 AudioRecord：每個輸入裝置 × 來源 × 取樣率，一般模式與通話模式各跑一輪。
//     判定用「去掉直流偏移後」的 AC 音量，避免固定雜訊被誤判成有聲音。
//  B. WebView getUserMedia（與電視 App 同一條路徑）：指定 USB 鏡頭麥克風與預設麥克風各錄 4 秒，
//     在網頁端算 AC 音量並回傳。若 A 有聲音而 B 無聲音，問題就在 WebView／Chromium 的音訊路徑。
public class MainActivity extends Activity {
    private static final String TAG = "MicCheck";

    private static final int[] SOURCES = {
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.UNPROCESSED,
    };
    private static final String[] SOURCE_NAMES = {"MIC", "VOICE_COMM", "UNPROCESSED"};
    private static final int[] RATES = {48000, 16000};

    private WebView web;
    private PermissionRequest pendingWebPerm;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i(TAG, "WEB| " + m.message());
                return true;
            }
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }
        });
        new Thread(this::runA).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 等 AudioRecord 測試跑完再進 WebView 測試
        if (!webStarted && aDone) startB();
    }

    private volatile boolean aDone = false;
    private boolean webStarted = false;

    private void startB() {
        webStarted = true;
        runOnUiThread(() -> web.loadDataWithBaseURL("https://miccheck.local/", PAGE, "text/html", "utf-8", null));
    }

    // ---------- A：原生 AudioRecord ----------
    private void runA() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        List<AudioDeviceInfo> inputs = new ArrayList<>();
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            int t = d.getType();
            // 15 內建麥克風、11 USB 裝置、3 有線耳機麥克風（這裡是遙控器的麥克風）
            if (t == AudioDeviceInfo.TYPE_BUILTIN_MIC || t == AudioDeviceInfo.TYPE_USB_DEVICE || t == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                inputs.add(d);
            }
        }
        log("=== BEGIN A  輸入裝置：" + inputs.size());
        for (AudioDeviceInfo d : inputs) log("裝置 " + d.getProductName() + " type=" + d.getType() + " id=" + d.getId());

        am.setMode(AudioManager.MODE_NORMAL);
        log("--- A1 一般模式");
        for (AudioDeviceInfo d : inputs) for (int si = 0; si < SOURCES.length; si++) for (int r : RATES) log(test(d, SOURCES[si], SOURCE_NAMES[si], r, "normal"));

        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        log("--- A2 通話模式 (MODE_IN_COMMUNICATION)");
        for (AudioDeviceInfo d : inputs) for (int si = 0; si < SOURCES.length; si++) for (int r : RATES) log(test(d, SOURCES[si], SOURCE_NAMES[si], r, "incall"));
        am.setMode(AudioManager.MODE_NORMAL);

        log("=== END A");
        aDone = true;
        runOnUiThread(() -> { if (!webStarted) startB(); });
    }

    private String test(AudioDeviceInfo dev, int source, String srcName, int rate, String mode) {
        String tag = mode + " | " + dev.getProductName() + " | " + srcName + " | " + rate;
        int minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) return tag + " → 不支援";
        AudioRecord rec = null;
        try {
            rec = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, 4096) * 2);
            if (!rec.setPreferredDevice(dev)) return tag + " → 無法指定裝置";
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) return tag + " → 初始化失敗";
            rec.startRecording();
            short[] buf = new short[rate / 50];
            int reads = 0, zero = 0;
            long n = 0; double sum = 0, sumSq = 0; int peak = 0;
            long end = System.currentTimeMillis() + 1500;
            while (System.currentTimeMillis() < end) {
                int k = rec.read(buf, 0, buf.length);
                if (k <= 0) { zero++; continue; }
                reads++;
                for (int i = 0; i < k; i++) {
                    double v = buf[i];
                    sum += v; sumSq += v * v; n++;
                    peak = Math.max(peak, Math.abs(buf[i]));
                }
            }
            double mean = n > 0 ? sum / n : 0;
            double ac = n > 0 ? Math.sqrt(Math.max(0, sumSq / n - mean * mean)) : 0;
            String verdict = reads == 0 ? "無資料 ✗" : (ac > 30 ? "有聲音 ✓" : "無變化 ✗");
            return tag + " → ac=" + (int) ac + " dc=" + (int) mean + " peak=" + peak + " reads=" + reads + " zero=" + zero + " → " + verdict;
        } catch (Exception e) {
            return tag + " → 例外 " + e;
        } finally {
            if (rec != null) { try { rec.stop(); } catch (Exception ignored) {} rec.release(); }
        }
    }

    private void log(String s) { Log.i(TAG, s); }

    // ---------- B：WebView getUserMedia（與電視 App 相同路徑） ----------
    // 網頁會：列出裝置 → 用 USB 麥克風與預設麥克風各錄 4 秒 → 算 AC 音量 → console.log 結果
    private static final String PAGE =
        "<!doctype html><meta charset=utf-8><body>micwebtest</body><script>\n" +
        "const USB = /logitech|streamcam|usb/i;\n" +
        "function log(s){ console.log(s); }\n" +
        "async function level(deviceId, label){\n" +
        "  let stream;\n" +
        "  try { stream = await navigator.mediaDevices.getUserMedia({audio: deviceId ? {deviceId:{exact:deviceId}, echoCancellation:false, noiseSuppression:false, autoGainControl:false} : {echoCancellation:false, noiseSuppression:false, autoGainControl:false}}); }\n" +
        "  catch(e){ log('WEBMIC '+label+' getUserMedia 失敗 '+e.name); return; }\n" +
        "  const track = stream.getAudioTracks()[0];\n" +
        "  log('WEBMIC '+label+' track.label='+track.label+' settings.deviceId='+(track.getSettings().deviceId||'?').slice(0,8)+' readyState='+track.readyState);\n" +
        "  const ctx = new (window.AudioContext||window.webkitAudioContext)();\n" +
        "  log('WEBMIC '+label+' ctx.state='+ctx.state);\n" +
        "  await ctx.resume().catch(()=>{});\n" +
        "  const src = ctx.createMediaStreamSource(stream);\n" +
        "  const an = ctx.createAnalyser(); an.fftSize = 2048; src.connect(an);\n" +
        "  const buf = new Float32Array(an.fftSize);\n" +
        "  let n=0, sum=0, sumSq=0, peak=0, samples=0;\n" +
        "  const t0 = Date.now();\n" +
        "  await new Promise(res => { const id = setInterval(() => {\n" +
        "    an.getFloatTimeDomainData(buf);\n" +
        "    for (const v of buf){ sum+=v; sumSq+=v*v; samples++; if(Math.abs(v)>peak) peak=Math.abs(v); }\n" +
        "    n++;\n" +
        "    if (Date.now()-t0 > 4000){ clearInterval(id); res(); }\n" +
        "  }, 50); });\n" +
        "  const mean = sum/samples, ac = Math.sqrt(Math.max(0, sumSq/samples - mean*mean));\n" +
        "  log('WEBMIC '+label+' ac='+(ac*32768).toFixed(1)+' peak='+(peak*32768).toFixed(0)+' dc='+(mean*32768).toFixed(1)+' ctx.state='+ctx.state+' → '+(ac*32768>30?'有聲音 ✓':'無變化 ✗'));\n" +
        "  stream.getTracks().forEach(t=>t.stop()); ctx.close();\n" +
        "}\n" +
        "(async () => {\n" +
        "  try {\n" +
        "    await navigator.mediaDevices.getUserMedia({audio:true}).then(s=>s.getTracks().forEach(t=>t.stop()));\n" +
        "    const devs = await navigator.mediaDevices.enumerateDevices();\n" +
        "    const ins = devs.filter(d=>d.kind==='audioinput');\n" +
        "    log('WEBMIC 可見輸入裝置：' + ins.map(d=>(d.label||'(無名)')+'['+d.deviceId.slice(0,6)+']').join(' ｜ '));\n" +
        "    const usb = ins.find(d=>USB.test(d.label));\n" +
        "    if (usb) await level(usb.deviceId, 'USB'); else log('WEBMIC 找不到 USB 麥克風');\n" +
        "    await level(null, '預設');\n" +
        "    log('WEBMIC END');\n" +
        "  } catch(e){ log('WEBMIC 例外 '+e); }\n" +
        "})();\n" +
        "</script>";
}
