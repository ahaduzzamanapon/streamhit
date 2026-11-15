#!/usr/bin/env python3
"""
Multi-Server Sports Aggregator
Merges live matches from multiple providers (SonyLIV, Tapmad, Willow, FanCode)
and groups multiple streaming server URLs under the same match.
"""

import requests
import json
import re

KNOWN_PAIRS = [
    ('india', 'new zealand', 'India vs New Zealand (5th ODI)'),
    ('south africa', 'bangladesh', 'South Africa vs Bangladesh (1st Test)'),
    ('australia', 'england', 'Australia vs England'),
    ('afghanistan', 'zimbabwe', 'Afghanistan vs Zimbabwe (2nd Test)'),
]

def extract_event_key(title):
    if not title:
        return None, title
    t_lower = title.lower()
    for team_a, team_b, clean_title in KNOWN_PAIRS:
        if team_a in t_lower and team_b in t_lower:
            return f"{team_a}_vs_{team_b}", clean_title
    # Fallback normalization
    words = sorted([w for w in re.findall(r'[a-z]+', t_lower) if w not in ['tour', 'live', 'day', 'match', 'in', 'of', 'and', 'vs', 'v']])
    return '_'.join(words[:4]), title

def run():
    grouped = {}

    def add_server(key, canonical_title, thumbnail, server_label, url, extra=None):
        if key not in grouped:
            grouped[key] = {
                'title': canonical_title,
                'thumbnail': thumbnail,
                'stream_links': []
            }
        server_entry = {
            'label': f"Server {len(grouped[key]['stream_links']) + 1} ({server_label})",
            'url': url
        }
        if extra:
            server_entry.update(extra)
        grouped[key]['stream_links'].append(server_entry)

    # 1. SONYLIV
    try:
        r = requests.get('https://raw.githubusercontent.com/drmlive/sliv-live-events/refs/heads/main/sonyliv.m3u', timeout=8)
        if r.status_code == 200:
            lines = r.text.splitlines()
            cur_title = None
            cur_logo = None
            for line in lines:
                if line.startswith('#EXTINF'):
                    m = re.search(r'tvg-logo="([^"]*)"', line)
                    if m: cur_logo = m.group(1)
                    cur_title = line.split(',')[-1].strip()
                elif line.startswith('http'):
                    k, ct = extract_event_key(cur_title)
                    # Extract language if present
                    lang = 'HD'
                    for l in ['ENG', 'HIN', 'TAM', 'TEL', 'KAN']:
                        if f"[{l}]" in cur_title or f"/{l}/" in line:
                            lang = l
                            break
                    add_server(k, ct, cur_logo, f"SonyLIV {lang}", line.strip())
    except Exception as e:
        print("SonyLIV error:", e)

    # 2. WILLOW
    try:
        r = requests.get('https://raw.githubusercontent.com/srhady/willow-event/refs/heads/main/live_sports.m3u', timeout=8)
        if r.status_code == 200:
            lines = r.text.splitlines()
            cur_title = None
            cur_logo = None
            cur_key = None
            for line in lines:
                if line.startswith('#EXTINF'):
                    m = re.search(r'tvg-logo="([^"]*)"', line)
                    if m: cur_logo = m.group(1)
                    cur_title = line.split(',')[-1].strip()
                elif 'license_key=' in line:
                    cur_key = line.split('license_key=')[-1].strip()
                elif line.startswith('http'):
                    k, ct = extract_event_key(cur_title)
                    extra = {'license_key': cur_key} if cur_key else None
                    add_server(k, ct, cur_logo, "Willow HD (Dash)", line.strip(), extra)
                    cur_key = None
    except Exception as e:
        print("Willow error:", e)

    # 3. TAPMAD
    try:
        r = requests.get('https://raw.githubusercontent.com/sm-monirulislam/Tapmad_Auto_Update_Playlist/main/tapmad_data.json', timeout=8)
        if r.status_code == 200:
            for m in r.json().get('matches', []):
                if m.get('status') == 'LIVE' and m.get('stream_url'):
                    k, ct = extract_event_key(m.get('title'))
                    extra = {'headers': {'Referer': 'https://www.tapmad.com/'}}
                    add_server(k, ct, m.get('thumbnail'), "Tapmad Live (HLS)", m.get('stream_url'), extra)
    except Exception as e:
        print("Tapmad error:", e)

    return list(grouped.values())

if __name__ == '__main__':
    events = run()
    print(f"\n✅ Total Consolidated Matches: {len(events)}")
    for ev in events:
        print(f"\n🏏 {ev['title']}")
        print(f"   Total Servers Available: {len(ev['stream_links'])}")
        for s in ev['stream_links']:
            print(f"    👉 {s['label']}")
