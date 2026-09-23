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
            req_log = os.path.join(base_dir, "scratch/wsgi_access.log")
            req_err = os.path.join(base_dir, "scratch/wsgi_request_error.log")
            method = environ.get('REQUEST_METHOD')
            path = environ.get('PATH_INFO')
            try:
                with open(req_log, "a") as f:
                    f.write(f"WSGI IN: {method} {path} len={environ.get('CONTENT_LENGTH')}\n")
            except:
                pass

            def logged_start_response(status, headers, exc_info=None):
                try:
                    with open(req_log, "a") as f:
                        f.write(f"WSGI OUT: {method} {path} -> {status}\n")
                except:
                    pass
                return start_response(status, headers, exc_info) if exc_info else start_response(status, headers)

            try:
                if self._middleware is None:
                    with self._lock:
                        if self._middleware is None:
                            self._middleware = ASGIMiddleware(self.app)
                iterable = self._middleware(environ, logged_start_response)

                def generate():
                    try:
                        for chunk in iterable:
                            yield chunk
                    except Exception as ge:
                        import traceback
                        with open(req_err, "a") as f:
                            f.write(f"\n--- WSGI ITERATOR ERROR: {ge} ---\n")
                            f.write(f"METHOD: {method} PATH: {path}\n")
                            f.write(traceback.format_exc() + "\n")
                        raise
                    finally:
                        if hasattr(iterable, "close"):
                            try:
                                iterable.close()
                            except:
                                pass
                return generate()
            except Exception as e:
                import traceback
                with open(req_err, "a") as f:
                    f.write(f"\n--- WSGI ERROR: {e} ---\n")
                    f.write(f"METHOD: {method} PATH: {path}\n")
                    f.write(traceback.format_exc() + "\n")
                raise

    application = LazyASGIMiddleware(app)
    
    with open(error_log, "a") as f:
        f.write("Passenger WSGI loaded application wrapper successfully!\n")
except Exception as e:
    import traceback
    with open(error_log, "a") as f:
        f.write(f"Passenger WSGI Import/Startup Error: {e}\n")
        f.write(traceback.format_exc() + "\n")
    raise
