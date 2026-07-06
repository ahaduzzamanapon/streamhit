import asyncio
import sys
import os

# Add Moviebox-API to path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "Moviebox-API")))
import api

async def run_test():
    subject_id = "4360485895745717992"
    detail_path = "naruto-hindi-8iXhwtr47c5"
    se = 1
    ep = 4
    
    print(f"Testing api.py logic for subject {subject_id}, se={se}, ep={ep}...")
    try:
        res = await api.get_stream_sources(subject_id, detail_path, se, ep)
        import json
        print("STREAM SOURCES RESPONSE:")
        print(json.dumps(res, indent=2))
    except Exception as e:
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    asyncio.run(run_test())
