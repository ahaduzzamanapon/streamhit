import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import sports_updater

data = sports_updater.get_live_sports_data(force_refresh=True)
print("Total matches:", data["total_matches"])
print("Live:", data["live_count"], "Upcoming:", data["upcoming_count"])
for m in data["list"]:
    print(f"[{m['status_display']}] {m['title']} ({m['sport']}) - {len(m['stream_links'])} servers")
    for s in m["stream_links"][:3]:
        print(f"   - {s['label']}: {s['url'][:65]}...")
