# FastAPI ASGI -> WSGI bridge for cPanel Passenger
# NOTE: Do NOT add sys.path manipulation here - it causes recursive loading
import asyncio
import threading
from a2wsgi import ASGIMiddleware
from main import app

# Create a single global event loop for the entire process lifetime
loop = asyncio.new_event_loop()

def run_background_loop(loop):
    asyncio.set_event_loop(loop)
    loop.run_forever()

t = threading.Thread(target=run_background_loop, args=(loop,), daemon=True)
t.start()

application = ASGIMiddleware(app, loop=loop)
