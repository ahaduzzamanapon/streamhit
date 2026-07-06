import sys
import asyncio
import os

# Add local path to sys.path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

import main

async def test():
    try:
        res = await main.get_resource('4360485895745717992', se=1, ep=4)
        print('SUCCESS Result:', res)
    except Exception as e:
        import traceback
        print('FAILED with Exception:')
        traceback.print_exc()

if __name__ == "__main__":
    asyncio.run(test())
