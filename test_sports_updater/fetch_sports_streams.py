#!/usr/bin/env python3
"""
Live Sports Extractor (FanCode & Tapmad Direct API)
Fetches live sports matches and streaming data directly from official platform APIs.
Compatible with nxly.online /api/sports/live format.
"""

import requests
import json
import uuid
from datetime import datetime

class SportsDataFetcher:
    def __init__(self):
        self.headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36'
        }

    def fetch_fancode_live(self):
        """Fetch live matches directly from FanCode GraphQL API"""
        url = 'https://www.fancode.com/graphql'
        headers = dict(self.headers)
        headers.update({
            'Content-Type': 'application/json',
            'source': 'fancodetv',
            'tvplatform': 'fancode',
            'appversion': '3.0.1',
            'buildversion': '179',
            'deviceId': str(uuid.uuid4())
        })

        query = '''
        query LiveStreamingMatches {
          matchesHasLivestream {
            id
            name
            streamingStatus
            isPremium
            startTime
            sport { name }
            squads { shortName name }
          }
        }
        '''
        matches = []
        try:
            resp = requests.post(url, json={'query': query, 'variables': {}}, headers=headers, timeout=10)
            if resp.status_code == 200:
                data = resp.json().get('data', {}).get('matchesHasLivestream', [])
                for m in data:
                    if m.get('streamingStatus') == 'STARTED':
                        matches.append({
                            'source': 'FanCode',
                            'id': m.get('id'),
                            'title': m.get('name'),
                            'sport': m.get('sport', {}).get('name', 'Sports'),
                            'status': 'LIVE',
                            'start_time': m.get('startTime')
                        })
        except Exception as e:
            print(f"Error fetching FanCode: {e}")
        return matches

    def fetch_tapmad_live(self):
        """Fetch live matches directly from Tapmad Backend API"""
        url = 'https://backend-api.tapmad.com/api/getSportsDataV2'
        headers = dict(self.headers)
        headers.update({
            'Origin': 'https://www.tapmad.com',
            'Referer': 'https://www.tapmad.com/'
        })
        params = {
            'headerType': '0',
            'verticalStart': '0',
            'verticalEnd': '4',
            'horizontalStart': '0',
            'horizontalEnd': '10',
            'Platform': 'web'
        }
        matches = []
        try:
            resp = requests.get(url, params=params, headers=headers, timeout=10)
            if resp.status_code == 200:
                tabs = resp.json().get('Tabs', [])
                for tab in tabs:
                    for v in tab.get('Videos', []):
                        matches.append({
                            'source': 'Tapmad',
                            'id': v.get('ContentEntityId'),
                            'title': v.get('ContentVideoName'),
                            'description': v.get('ContentDescription'),
                            'thumbnail': v.get('ChannelTVImage') or v.get('ContentImage'),
                            'status': 'LIVE' if tab.get('SectionName') == 'Live Premium' else 'UPCOMING'
                        })
        except Exception as e:
            print(f"Error fetching Tapmad: {e}")
        return matches

if __name__ == '__main__':
    fetcher = SportsDataFetcher()
    print("Fetching FanCode live matches...")
    fc = fetcher.fetch_fancode_live()
    print(f"Found {len(fc)} FanCode live matches:")
    for m in fc:
        print(f" - [{m['sport']}] {m['title']} (ID: {m['id']})")

    print("\nFetching Tapmad matches...")
    tm = fetcher.fetch_tapmad_live()
    print(f"Found {len(tm)} Tapmad matches:")
    for m in tm:
        print(f" - [{m['status']}] {m['title']} (ID: {m['id']})")

