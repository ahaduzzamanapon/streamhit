import sys
import os

# Add the application directory to the python path
sys.path.insert(0, os.path.dirname(__file__))

# Import the ASGI to WSGI adapter
from a2wsgi import ASGIMiddleware

# Import our FastAPI application instance from main.py
from main import app

# Wrap the FastAPI application to expose a WSGI application interface for cPanel Passenger
application = ASGIMiddleware(app)
