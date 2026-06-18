import asyncio
import json
from main import request_h5_api

async def test():
    # ID of "Challenger Coaches the WORST PLAYER in League of Legends" from logs
    res = await request_h5_api('GET', '/wefeed-h5api-bff/detail?subjectId=6586140004211739688')
    print(json.dumps(res.get('data', {}).get('subject', {}), indent=2))

if __name__ == "__main__":
    asyncio.run(test())
