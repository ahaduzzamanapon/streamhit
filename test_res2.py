import sys, asyncio
sys.path.insert(0, '/home/lcsyxfen/streamhit.lc-synergy.ltd')
import main

async def test():
    res = await main.get_resource('9028867555875774472', se=1, ep=1)
    print('Result:', res)

asyncio.run(test())
