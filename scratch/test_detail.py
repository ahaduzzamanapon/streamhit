import asyncio
import json
from main import request_h5_api

async def test():
    res = await request_h5_api('GET', '/wefeed-h5api-bff/detail?subjectId=933231867443270976')
    print(json.dumps(res.get('data', {}).get('subject', {}), indent=2))

if __name__ == "__main__":
    asyncio.run(test())