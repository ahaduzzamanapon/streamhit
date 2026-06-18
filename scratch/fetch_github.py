import httpx
import re
html = httpx.get('https://github.com/shafat-96/moviebox-hls').text
print(re.findall(r'href="(/shafat-96/moviebox-hls/blob/main/[^"]+)"', html))
print(re.findall(r'href="(/shafat-96/moviebox-hls/tree/main/[^"]+)"', html))
