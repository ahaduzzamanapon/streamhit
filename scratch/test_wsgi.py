import sys
import os

# Set up environment variables that might be needed
os.environ["DB_HOST"] = "127.0.0.1"

# Import passenger_wsgi
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import passenger_wsgi

print("Imported passenger_wsgi successfully.")

# Construct a mock WSGI environ for GET /
environ = {
    'REQUEST_METHOD': 'GET',
    'SCRIPT_NAME': '',
    'PATH_INFO': '/',
    'QUERY_STRING': '',
    'SERVER_NAME': '127.0.0.1',
    'SERVER_PORT': '80',
    'SERVER_PROTOCOL': 'HTTP/1.1',
    'wsgi.version': (1, 0),
    'wsgi.url_scheme': 'http',
    'wsgi.input': sys.stdin.buffer,
    'wsgi.errors': sys.stderr,
    'wsgi.multithread': False,
    'wsgi.multiprocess': False,
    'wsgi.run_once': False,
}

def start_response(status, headers, exc_info=None):
    print(f"Status: {status}")
    print("Headers:", headers)

print("\nCalling WSGI application for GET /...")
try:
    response = passenger_wsgi.application(environ, start_response)
    print("Received response iterator.")
    for chunk in response:
        print(f"Chunk length: {len(chunk)}")
        # Print first 100 chars
        print(chunk[:100].decode('utf-8', errors='ignore'))
        print("...")
    print("Finished response iteration successfully!")
except Exception as e:
    import traceback
    print("Failed to run WSGI application:", e)
    traceback.print_exc()
