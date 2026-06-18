import httpx
import re
html = httpx.get('https://github.com/shafat-96/moviebox-hls/tree/main/api').text
print(re.findall(r'href="(/shafat-96/moviebox-hls/blob/main/api/[^"]+)"', html))
print(re.findall(r'href="(/shafat-96/moviebox-hls/tree/main/api/[^"]+)"', html))
