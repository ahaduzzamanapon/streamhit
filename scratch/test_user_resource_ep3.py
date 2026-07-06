import asyncio
import sys
import os

# Add Moviebox-API to path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "Moviebox-API")))
import api

async def run_test():
    subject_id = "4360485895745717992"
    detail_path = "naruto-hindi-8iXhwtr47c5"
    
    print("Testing S1E3...")
    res = await api.get_stream_sources(subject_id, detail_path, 1, 3)
    import json
    print("STREAM SOURCES RESPONSE for EP 3:")
    print(json.dumps(res, indent=2))

if __name__ == "__main__":
    asyncio.run(run_test())
