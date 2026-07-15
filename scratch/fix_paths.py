import os

base_dir = r'E:\P\movie bot\public'
files = ['index.html', 'movies.html', 'tv.html', 'live-tv.html', 'details.html', 'watch.html']

for file in files:
    path = os.path.join(base_dir, file)
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # Fix paths
    content = content.replace('href="style.css', 'href="/style.css')
    content = content.replace('src="app.js', 'src="/app.js')
    content = content.replace('href="favicon.svg', 'href="/favicon.svg')
    
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)

print("Fixed HTML relative paths!")
