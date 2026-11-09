// 共用的信令與 WebRTC 工具：SSE 接收、HTTP POST 送出、TURN 憑證、編碼器偏好、連線看門狗。
// 房間 ID 來自網址的 ?room=，沒有就用 default（投屏頁面用）。
(function () {
  const params = new URLSearchParams(location.search);
  let ROOM = params.get('room') || 'default';
  let es = null;
  let retryTimer = null;

  // 角色連線，斷線自動重連；room 可指定房間（通話頁面用配對後取得的房間 ID）
  let closed = false;
  function connect(role, onMessage, room) {
    if (room) ROOM = room;
    closed = false;
    clearTimeout(retryTimer);
    if (es) es.close();
    es = new EventSource(`/events?role=${role}&room=${encodeURIComponent(ROOM)}`);
    es.onopen = () => window.dispatchEvent(new Event('signal-open'));   // M14：連線建立後才送 hello
    es.onmessage = (e) => {
      try { onMessage(JSON.parse(e.data)); } catch (err) { console.error(err); }
    };
    es.onerror = () => {
      if (closed) return;                  // 掛斷後不再重連
      es.close();
      clearTimeout(retryTimer);
      window.dispatchEvent(new Event('signal-lost'));
      retryTimer = setTimeout(() => connect(role, onMessage, ROOM), 2000);
    };
  }
  // M11：掛斷或停止時真正關閉 SSE
  function close() {
    closed = true;
    clearTimeout(retryTimer);
    if (es) { es.close(); es = null; }
  }

  // from 可省略：mac/tv 之間互傳時自動推斷；通話頁面請明確傳入
  function send(to, type, data, from) {
    if (!from) from = to === 'mac' ? 'tv' : 'mac';
    return fetch(`/send?room=${encodeURIComponent(ROOM)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ to, from, type, data }),
    }).then((r) => {
      if (!r.ok) throw new Error('send ' + r.status);
      return true;
    }).catch(() => {
      window.dispatchEvent(new Event('signal-send-failed'));
      return false;
    });
  }

  // 取得 ICE 設定（STUN + TURN）；失敗時退回只用 STUN，區網仍可用
  async function iceConfig() {
    try {
      const r = await fetch('/turn');
      if (r.ok) return await r.json();
    } catch (e) { /* ignore */ }
    return { iceServers: [] };
  }

  // 編碼器偏好：H.264 排第一。電視有硬體 H.264 編碼器，iPhone 也是硬體，雙方都省 CPU。
  function preferH264(pc) {
    if (!window.RTCRtpSender || !RTCRtpSender.getCapabilities) return;
    const caps = RTCRtpSender.getCapabilities('video');
    if (!caps || !caps.codecs) return;
    const isH264 = (c) => /h264/i.test(c.mimeType);
    const h264 = caps.codecs.filter(isH264);
    if (!h264.length) return;
    const ordered = [...h264, ...caps.codecs.filter((c) => !isH264(c))];
    pc.getTransceivers().forEach((t) => {
      const kind = (t.sender && t.sender.track && t.sender.track.kind)
        || (t.receiver && t.receiver.track && t.receiver.track.kind);
      if (kind !== 'video' || typeof t.setCodecPreferences !== 'function') return;
      try { t.setCodecPreferences(ordered); } catch (e) { /* 不支援就維持預設 */ }
    });
  }

  // 送出端上限：位元率、幀率；畫質不夠時優先保幀率（動態畫面比較順）
  async function limitVideoSender(pc, opts) {
    for (const s of pc.getSenders()) {
      if (!s.track || s.track.kind !== 'video') continue;
      try {
        const p = s.getParameters();
        if (!p.encodings || !p.encodings.length) p.encodings = [{}];
        if (opts.maxBitrate) p.encodings[0].maxBitrate = opts.maxBitrate;
        if (opts.maxFramerate) p.encodings[0].maxFramerate = opts.maxFramerate;
        if (opts.scale) p.encodings[0].scaleResolutionDownBy = opts.scale;
        p.degradationPreference = 'maintain-framerate';
        await s.setParameters(p);
      } catch (e) { /* 某些瀏覽器不接受部分欄位，忽略 */ }
      try { if (s.track.contentHint !== undefined) s.track.contentHint = 'motion'; } catch (e) { /* ignore */ }
    }
  }

  // 連線狀態中文
  // 三段式大字狀態：家人只需要知道「畫面順不順」與「是不是斷了」
  const STATE_TEXT = {
    new: '準備中…', connecting: '連線中…', connected: '畫面順暢',
    disconnected: '網路不穩，畫面可能變模糊', failed: '連線中斷，正在重連', closed: '已結束',
  };
  function stateText(s) { return STATE_TEXT[s] || s; }

  // 連線看門狗：disconnected 等 4 秒仍未恢復 → onRecover（ICE 重啟）；failed → onFail
  function watchConnection(pc, handlers) {
    let timer = null;
    pc.addEventListener('connectionstatechange', () => {
      const s = pc.connectionState;
      if (handlers.onState) handlers.onState(s);
      clearTimeout(timer); timer = null;
      if (s === 'disconnected') {
        timer = setTimeout(() => {
          if (pc.connectionState === 'disconnected' && handlers.onRecover) handlers.onRecover();
        }, 4000);
      } else if (s === 'failed') {
        if (handlers.onFail) handlers.onFail();
      }
    });
  }

  // 統計摘要：接收／送出的解析度與幀率、延遲、路徑、送出端被什麼限制（cpu / bandwidth）
  async function statsSummary(pc) {
    const rep = await pc.getStats();
    let inV = null, outV = null, pair = null, local = null;
    rep.forEach((s) => {
      if (s.type === 'inbound-rtp' && s.kind === 'video') inV = s;
      if (s.type === 'outbound-rtp' && s.kind === 'video') outV = s;
      if (s.type === 'candidate-pair' && s.nominated && s.state === 'succeeded') pair = s;
    });
    if (pair) local = rep.get(pair.localCandidateId);
    const fmt = (v) => (v && v.frameWidth ? `${v.frameWidth}x${v.frameHeight} ${v.framesPerSecond != null ? Math.round(v.framesPerSecond) : '-'}fps` : '-');
    const rtt = pair && pair.currentRoundTripTime != null ? `${Math.round(pair.currentRoundTripTime * 1000)}ms` : '-';
    const path = local ? ({ host: '直連', srflx: '直連(NAT)', prflx: '直連', relay: '中繼' }[local.candidateType] || local.candidateType) : '-';
    const limit = outV && outV.qualityLimitationReason && outV.qualityLimitationReason !== 'none'
      ? `（送出受限：${outV.qualityLimitationReason === 'cpu' ? 'CPU' : outV.qualityLimitationReason === 'bandwidth' ? '頻寬' : outV.qualityLimitationReason}）` : '';
    return `接收 ${fmt(inV)} ｜ 送出 ${fmt(outV)}${limit} ｜ 延遲 ${rtt} ｜ ${path}`;
  }

  // 本機儲存小工具（私密模式或被擋時不會丟例外）
  const store = {
    get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* ignore */ } },
    del(k) { try { localStorage.removeItem(k); } catch (e) { /* ignore */ } },
  };

  window.ROOM = ROOM;
  window.Signal = { connect, close };
  window.send = send;
  window.iceConfig = iceConfig;
  window.preferH264 = preferH264;
  window.limitVideoSender = limitVideoSender;
  window.stateText = stateText;
  window.watchConnection = watchConnection;
  window.statsSummary = statsSummary;
  window.store = store;
})();
