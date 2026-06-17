# FastAPI ASGI -> WSGI bridge for cPanel Passenger
# NOTE: Do NOT add sys.path manipulation here - it causes recursive loading
import os
import sys

base_dir = os.path.dirname(os.path.abspath(__file__))
error_log = os.path.join(base_dir, "scratch/passenger_error.log")

try:
    from a2wsgi import ASGIMiddleware
    from main import app

    application = ASGIMiddleware(app)
    
    with open(error_log, "a") as f:
        f.write("Passenger WSGI loaded application successfully!\n")
except Exception as e:
    import traceback
    with open(error_log, "a") as f:
        f.write(f"Passenger WSGI Import/Startup Error: {e}\n")
        f.write(traceback.format_exc() + "\n")
    raise
