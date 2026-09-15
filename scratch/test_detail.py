import sys, asyncio, json
sys.path.insert(0, '/home/lcsyxfen/streamhit.lc-synergy.ltd')
import main

async def test():
    res = await main.request_h5_api('GET', '/wefeed-h5api-bff/detail?subjectId=3708565066648798288', host='https://h5-api.aoneroom.com')
    print('Result:', json.dumps(res, indent=2))

asyncio.run(test())
