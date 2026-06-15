import sys
import os
import traceback

# Add the application directory to the python path
sys.path.insert(0, os.path.dirname(__file__))

LOG_FILE = os.path.join(os.path.dirname(__file__), "passenger_errors.log")

try:
    # Import the ASGI to WSGI adapter
    from a2wsgi import ASGIMiddleware

    # Import our FastAPI application instance from main.py
    from main import app

    # Wrap the FastAPI application to expose a WSGI application interface for cPanel Passenger
    application = ASGIMiddleware(app)
except Exception as e:
    with open(LOG_FILE, "a") as f:
        f.write("--- PASSENGER STARTUP ERROR ---\n")
        traceback.print_exc(file=f)
        f.write("\n")
    # Expose a dummy application so Passenger doesn't crash silently
    def application(environ, start_response):
        start_response('500 Internal Server Error', [('Content-Type', 'text/plain')])
        return [b"Passenger Startup Error. Check passenger_errors.log for details."]
