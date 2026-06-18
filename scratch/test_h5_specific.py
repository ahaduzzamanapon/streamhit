import asyncio
import json
from test_h5_api import request_api

async def test():
    res = await request_api("GET", "/wefeed-h5api-bff/detail?subjectId=933231867443270976")
    print("Test H5 API success?", bool(res))
    if res:
        print(json.dumps(res, indent=2)[:500])

if __name__ == "__main__":
    asyncio.run(test())