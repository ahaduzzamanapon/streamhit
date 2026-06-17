import socket

host = "streamhit.lc-synergy.ltd"
print(f"Resolving host: {host}")
try:
    ip = socket.gethostbyname(host)
    print(f"IP address: {ip}")
except Exception as e:
    print("Error:", e)
