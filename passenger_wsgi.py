# FastAPI ASGI -> WSGI bridge for cPanel Passenger
# NOTE: Do NOT add sys.path manipulation here - it causes recursive loading
import os
import sys

base_dir = os.path.dirname(os.path.abspath(__file__))
error_log = os.path.join(base_dir, "scratch/passenger_error.log")

try:
    from a2wsgi import ASGIMiddleware
    from main import app
    import threading

    class LazyASGIMiddleware:
        def __init__(self, app):
            self.app = app
            self._middleware = None
            self._lock = threading.Lock()

        def __call__(self, environ, start_response):
            if self._middleware is None:
                with self._lock:
                    if self._middleware is None:
                        self._middleware = ASGIMiddleware(self.app)
            return self._middleware(environ, start_response)

    application = LazyASGIMiddleware(app)
    
    with open(error_log, "a") as f:
        f.write("Passenger WSGI loaded application wrapper successfully!\n")
except Exception as e:
    import traceback
    with open(error_log, "a") as f:
        f.write(f"Passenger WSGI Import/Startup Error: {e}\n")
        f.write(traceback.format_exc() + "\n")
    raise
