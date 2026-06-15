# FastAPI ASGI -> WSGI bridge for cPanel Passenger
# NOTE: Do NOT add sys.path manipulation here - it causes recursive loading
from a2wsgi import ASGIMiddleware
from main import app

application = ASGIMiddleware(app)
