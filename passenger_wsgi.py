# FastAPI ASGI -> WSGI bridge for cPanel Passenger
# NOTE: Do NOT add sys.path manipulation here - it causes recursive loading
import os
import sys

base_dir = os.path.dirname(os.path.abspath(__file__))
scratch_dir = os.path.join(base_dir, "scratch")
try:
    os.makedirs(scratch_dir, exist_ok=True)
except Exception:
    pass

error_log = os.path.join(scratch_dir, "passenger_error.log")

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
            try:
                if self._middleware is None:
                    with self._lock:
                        if self._middleware is None:
                            self._middleware = ASGIMiddleware(self.app)
                return self._middleware(environ, start_response)
            except Exception as e:
                import traceback
                req_err = os.path.join(base_dir, "scratch/wsgi_request_error.log")
                with open(req_err, "a") as f:
                    f.write(f"\n--- WSGI ERROR: {e} ---\n")
                    f.write(f"METHOD: {environ.get('REQUEST_METHOD')} PATH: {environ.get('PATH_INFO')}\n")
                    f.write(traceback.format_exc() + "\n")
                raise

    application = LazyASGIMiddleware(app)
    
    with open(error_log, "a") as f:
        f.write("Passenger WSGI loaded application wrapper successfully!\n")
except Exception as e:
    import traceback
    err_txt = f"Passenger WSGI Import/Startup Error: {e}\n{traceback.format_exc()}\n"
    sys.stderr.write(err_txt)
    try:
        with open(error_log, "a") as f:
            f.write(err_txt)
    except Exception:
        pass
    raise
