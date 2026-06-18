import asyncio
import json
from main import request_h5_api

async def t():
    res = await request_h5_api('GET', '/wefeed-h5api-bff/detail?subjectId=933231867443270976')
    print(res.get('data',{}).get('subject',{}).get('title'))
    print(res.get('data',{}).get('subject',{}).get('subjectType'))
    
if __name__ == "__main__":
    asyncio.run(t())