# FastAPI ASGI -> WSGI bridge for cPanel Passenger
# NOTE: Do NOT add sys.path manipulation here - it causes recursive loading
import asyncio
from a2wsgi import ASGIMiddleware
from main import app

# Create a single global event loop for the entire process lifetime
loop = asyncio.new_event_loop()
asyncio.set_event_loop(loop)

application = ASGIMiddleware(app, loop=loop)
