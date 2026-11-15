#!/usr/bin/env python3
import http.server
import socketserver
import urllib.parse
import urllib.request
import json
import sys

PORT = 5050

STREAMS = [
    {
        "id": 1,
        "title": "🇧🇩 South Africa vs Bangladesh (1st Test LIVE)",
        "type": "dash",
        "url": "https://otte.live.fly.ww.aiv-cdn.net/iad-nitro/live/clients/dash/enc/vmsojksiyc/out/v1/5d915e7f388946c09ed9d07fd40f9f96/cenc.mpd",
        "keyId": "3d872367d3ff927c4abf0198dfeb01d2",
        "key": "47945393588cf479b728df327f3bef53"
    },
    {
        "id": 2,
        "title": "🏏 New Zealand vs India 5th ODI (SonyLIV Live)",
        "type": "hls",
        "url": "https://sonyeventsglobal.akamaized.net/hls/live/2119931/cricodi_1411/ENG/std_lrh-800300010.m3u8?hdnea=exp=1794773901~acl=/*~id=18568893537024703536364067261935~hmac=b120a544aa557d631d78b06bb84efafe51526677601a8ccb221e57ca811ab514"
    },
    {
        "id": 3,
        "title": "🏏 Australia vs England (Tapmad Live)",
        "type": "hls_proxy",
        "url": "https://tencent-cdn.tapmad.com/v1/01a14abbcff31ea540a81c2b41b760/01a14abc06421567ee931e109fa577/main.m3u8",
        "referer": "https://www.tapmad.com/"
    },
    {
        "id": 4,
        "title": "🏏 Afghanistan vs Zimbabwe (Tapmad Live)",
        "type": "hls_proxy",
        "url": "https://multiregion-asia-cdn.tapmad.com/v1/01a1548c4faf15fc600b1e3e968408/01a1548c7fde1ea557841e1612cf76/main.m3u8",
        "referer": "https://www.tapmad.com/"
    }
]

HTML_PLAYER = """<!DOCTYPE html>
<html lang="bn">
<head>
  <meta charset="UTF-8">
  <title>Live Cricket & Sports Player</title>
  <script src="https://cdnjs.cloudflare.com/ajax/libs/shaka-player/4.7.11/shaka-player.ui.min.js"></script>
  <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/shaka-player/4.7.11/controls.min.css">
  <script src="https://cdn.jsdelivr.net/npm/hls.js@latest"></script>
  <style>
    body { font-family: system-ui, sans-serif; background: #0b0f19; color: #fff; padding: 20px; text-align: center; margin: 0; }
    h1 { color: #38bdf8; margin: 10px 0; }
    p { color: #94a3b8; font-size: 14px; margin: 0 0 15px; }
    .video-wrapper { max-width: 860px; margin: 0 auto; background: #000; border-radius: 12px; overflow: hidden; border: 1px solid #1e293b; box-shadow: 0 15px 40px rgba(0,0,0,0.8); }
    video { width: 100%; aspect-ratio: 16/9; display: block; outline: none; }
    .channels { display: flex; flex-wrap: wrap; justify-content: center; gap: 12px; margin: 20px auto; max-width: 860px; }
    .btn { background: #1e293b; border: 1px solid #334155; color: #f1f5f9; padding: 12px 18px; border-radius: 8px; font-size: 14px; font-weight: 600; cursor: pointer; transition: all 0.2s; }
    .btn:hover { background: #0284c7; border-color: #38bdf8; transform: translateY(-2px); }
    .btn.active { background: #0284c7; border-color: #38bdf8; color: #fff; box-shadow: 0 0 15px rgba(56,189,248,0.4); }
    .status { margin-top: 15px; font-size: 14px; color: #10b981; font-weight: 500; }
  </style>
</head>
<body>
  <h1>🏏 Live Sports Stream Player</h1>
  <p>নিচের ম্যাচগুলোতে ক্লিক করলেই লাইভ খেলা দেখতে পাবেন</p>

  <div class="video-wrapper">
    <video id="videoPlayer" controls autoplay muted playsinline></video>
  </div>

  <div class="channels" id="channelList"></div>
  <div class="status" id="statusText">প্লেয়ার লোড হচ্ছে...</div>

  <script>
    const streams = __STREAMS_JSON__;
    const video = document.getElementById('videoPlayer');
    const channelList = document.getElementById('channelList');
    const statusText = document.getElementById('statusText');
    let hlsInstance = null;
    let shakaPlayer = null;

    streams.forEach((s, idx) => {
      const btn = document.createElement('button');
      btn.className = 'btn' + (idx === 0 ? ' active' : '');
      btn.innerText = s.title;
      btn.onclick = () => playStream(s, btn);
      channelList.appendChild(btn);
    });

    async function cleanup() {
      if (hlsInstance) {
        hlsInstance.destroy();
        hlsInstance = null;
      }
      if (shakaPlayer) {
        await shakaPlayer.destroy();
        shakaPlayer = null;
      }
    }

    async function playStream(stream, activeBtn) {
      document.querySelectorAll('.btn').forEach(b => b.classList.remove('active'));
      if (activeBtn) activeBtn.classList.add('active');

      await cleanup();
      statusText.innerText = 'লোডিং: ' + stream.title + '...';

      if (stream.type === 'dash') {
        shaka.polyfill.installAll();
        if (shaka.Player.isBrowserSupported()) {
          shakaPlayer = new shaka.Player(video);
          if (stream.keyId && stream.key) {
            shakaPlayer.configure({
              drm: {
                clearKeys: {
                  [stream.keyId]: stream.key
                }
              }
            });
          }
          try {
            await shakaPlayer.load(stream.url);
            statusText.innerText = '🟢 লাইভ চলছে: ' + stream.title;
            video.play().catch(e => console.log('Autoplay:', e));
          } catch (e) {
            statusText.innerText = '⚠️ এরর: ' + e.message;
          }
        }
      } else {
        let playUrl = stream.url;
        if (stream.type === 'hls_proxy') {
          playUrl = '/proxy?url=' + encodeURIComponent(stream.url) + '&ref=' + encodeURIComponent(stream.referer || '');
        }

        if (Hls.isSupported()) {
          hlsInstance = new Hls({ enableWorker: true });
          hlsInstance.loadSource(playUrl);
          hlsInstance.attachMedia(video);
          hlsInstance.on(Hls.Events.MANIFEST_PARSED, function() {
            statusText.innerText = '🟢 লাইভ চলছে: ' + stream.title;
            video.play().catch(e => console.log('Autoplay:', e));
          });
          hlsInstance.on(Hls.Events.ERROR, function(ev, data) {
            if (data.fatal) statusText.innerText = '⚠️ স্ট্রিম লোড করতে সমস্যা';
          });
        } else if (video.canPlayType('application/vnd.apple.mpegurl')) {
          video.src = playUrl;
          video.play();
          statusText.innerText = '🟢 লাইভ চলছে: ' + stream.title;
        }
      }
    }

    // Auto-play first stream (Bangladesh match)
    setTimeout(() => {
      if (streams.length > 0) playStream(streams[0], channelList.children[0]);
    }, 500);
  </script>
</body>
</html>
"""

class ProxyHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        query = urllib.parse.parse_qs(parsed.query)

        if path == '/' or path == '/player':
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.end_headers()
            html = HTML_PLAYER.replace('__STREAMS_JSON__', json.dumps(STREAMS))
            self.wfile.write(html.encode('utf-8'))
            return

        elif path == '/proxy':
            target_url = query.get('url', [''])[0]
            referer = query.get('ref', [''])[0]

            if not target_url:
                self.send_response(400)
                self.end_headers()
                self.wfile.write(b"Missing url")
                return

            req_headers = {
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
            }
            if referer:
                req_headers['Referer'] = referer
                req_headers['Origin'] = referer.rstrip('/')

            try:
                req = urllib.request.Request(target_url, headers=req_headers)
                with urllib.request.urlopen(req, timeout=10) as resp:
                    data = resp.read()
                    content_type = resp.headers.get('Content-Type', 'application/vnd.apple.mpegurl')

                    if target_url.endswith('.m3u8') or 'mpegurl' in content_type:
                        lines = data.decode('utf-8', errors='ignore').splitlines()
                        rewritten_lines = []
                        for line in lines:
                            sline = line.strip()
                            if sline and not sline.startswith('#'):
                                resolved = urllib.parse.urljoin(target_url, sline)
                                proxy_line = f"/proxy?url={urllib.parse.quote(resolved)}&ref={urllib.parse.quote(referer)}"
                                rewritten_lines.append(proxy_line)
                            else:
                                rewritten_lines.append(line)
                        data = '\n'.join(rewritten_lines).encode('utf-8')
                        content_type = 'application/vnd.apple.mpegurl'

                    self.send_response(200)
                    self.send_header('Content-Type', content_type)
                    self.send_header('Access-Control-Allow-Origin', '*')
                    self.send_header('Access-Control-Allow-Methods', 'GET, OPTIONS')
                    self.send_header('Access-Control-Allow-Headers', '*')
                    self.end_headers()
                    self.wfile.write(data)
            except Exception as e:
                self.send_response(502)
                self.send_header('Access-Control-Allow-Origin', '*')
                self.end_headers()
                self.wfile.write(f"Proxy Error: {e}".encode('utf-8'))
            return

        self.send_response(404)
        self.end_headers()

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', '*')
        self.end_headers()

    def log_message(self, format, *args):
        pass

if __name__ == '__main__':
    socketserver.TCPServer.allow_reuse_address = True
    with socketserver.TCPServer(('127.0.0.1', PORT), ProxyHandler) as httpd:
        print(f"Server started on http://localhost:{PORT}")
        sys.stdout.flush()
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            pass
