import os
import re

base_dir = r'E:\P\movie bot\public'

seo_template = """  <meta name="description" content="Watch free movies, TV shows, anime and live sports online.">
  <meta name="keywords" content="movies, tv shows, streaming, streamfit, watch free, hd movies, hindi dub, bengali dub, watch online">
  <!-- Open Graph / Facebook -->
  <meta property="og:type" content="website">
  <meta property="og:url" content="https://streamfit.ehealthfinder.com/">
  <meta property="og:title" content="Streamfit - Free Movies, TV Shows & Anime Streaming">
  <meta property="og:description" content="Watch free movies, TV shows, anime and live sports online.">
  <meta property="og:image" content="https://images.unsplash.com/photo-1594909122845-11baa439b7bf?w=1200&q=80">
  <!-- Twitter -->
  <meta property="twitter:card" content="summary_large_image">
  <meta property="twitter:url" content="https://streamfit.ehealthfinder.com/">
  <meta property="twitter:title" content="Streamfit - Free Movies, TV Shows & Anime Streaming">
  <meta property="twitter:description" content="Watch free movies, TV shows, anime and live sports online.">
  <meta property="twitter:image" content="https://images.unsplash.com/photo-1594909122845-11baa439b7bf?w=1200&q=80">
"""

files = ['index.html', 'movies.html', 'tv.html', 'live-tv.html', 'details.html', 'watch.html']

for file in files:
    path = os.path.join(base_dir, file)
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # Remove existing description, keywords, og tags, twitter tags if they exist to avoid duplicates
    content = re.sub(r'<meta name="description".*?>\n?', '', content)
    content = re.sub(r'<meta name="keywords".*?>\n?', '', content)
    content = re.sub(r'<!-- Open Graph.*?\n?', '', content)
    content = re.sub(r'<meta property="og:.*?>\n?', '', content)
    content = re.sub(r'<!-- Twitter.*?\n?', '', content)
    content = re.sub(r'<meta property="twitter:.*?>\n?', '', content)
    
    # Ensure index, follow is present (except for maybe noindex ones, but we want all indexed)
    if 'name="robots"' not in content:
        content = content.replace('<title>', '<meta name="robots" content="index, follow">\n  <title>')
    
    # Insert SEO template before </head>
    if '<!-- SCHEMA_PLACEHOLDER -->' in content:
        content = content.replace('<!-- SCHEMA_PLACEHOLDER -->', seo_template + '\n  <!-- SCHEMA_PLACEHOLDER -->')
    else:
        content = content.replace('</head>', seo_template + '\n</head>')
        
    with open(path, 'w', encoding='utf-8') as f:
        f.write(content)

# Update main.py to replace these new exact SEO strings
main_path = r'E:\P\movie bot\main.py'
with open(main_path, 'r', encoding='utf-8') as f:
    main_content = f.read()

old_reps = '''    reps = {
        '<title>Details - Streamfit</title>': f'<title>{meta["title"]}</title>',
        'id="detailsTitle">Title': f'id="detailsTitle">{meta["title"]}',
        'id="watchDescription">Description loading...': f'id="watchDescription">{meta["description"]}',
        'src="/default-cover.png"': f'src="{meta["cover"]}"',
        'content="No description available."': f'content="{meta["description"]}"',
        'content="Watch Faith Baldwin Romance Theatre - Streamfit"': f'content="{meta["title"]}"',
        'content="https://streamfit.ehealthfinder.com/details?id=128889733022893136&path="': f'content="{meta["url"]}"',
        '<!-- SCHEMA_PLACEHOLDER -->': meta.get("schema", "")
    }'''

new_reps = '''    reps = {
        '<title>Details - Streamfit</title>': f'<title>{meta["title"]}</title>',
        'id="detailsTitle">Title': f'id="detailsTitle">{meta["title"]}',
        'id="watchDescription">Description loading...': f'id="watchDescription">{meta["description"]}',
        'src="/default-cover.png"': f'src="{meta["cover"]}"',
        'content="Watch free movies, TV shows, anime and live sports online."': f'content="{meta["description"]}"',
        'content="Streamfit - Free Movies, TV Shows & Anime Streaming"': f'content="{meta["title"]}"',
        'content="https://streamfit.ehealthfinder.com/"': f'content="{meta["url"]}"',
        'content="https://images.unsplash.com/photo-1594909122845-11baa439b7bf?w=1200&q=80"': f'content="{meta["cover"]}"',
        'content="website"': 'content="video.movie"',
        '<!-- SCHEMA_PLACEHOLDER -->': meta.get("schema", "")
    }'''
main_content = main_content.replace(old_reps, new_reps)

old_reps2 = '''    reps = {
        '<title>Watch Online - Streamfit</title>': f'<title>Watching {meta["title"]}</title>',
        'content="No description available."': f'content="{meta["description"]}"',
        'content="Watch Faith Baldwin Romance Theatre - Streamfit"': f'content="{meta["title"]}"',
        'content="https://streamfit.ehealthfinder.com/details?id=128889733022893136&path="': f'content="{meta["url"]}"',
        '<!-- SCHEMA_PLACEHOLDER -->': meta.get("schema", "")
    }'''

new_reps2 = '''    reps = {
        '<title>Watch Online - Streamfit</title>': f'<title>Watching {meta["title"]}</title>',
        'content="Watch free movies, TV shows, anime and live sports online."': f'content="{meta["description"]}"',
        'content="Streamfit - Free Movies, TV Shows & Anime Streaming"': f'content="{meta["title"]}"',
        'content="https://streamfit.ehealthfinder.com/"': f'content="{meta["url"]}"',
        'content="https://images.unsplash.com/photo-1594909122845-11baa439b7bf?w=1200&q=80"': f'content="{meta["cover"]}"',
        'content="website"': 'content="video.movie"',
        '<!-- SCHEMA_PLACEHOLDER -->': meta.get("schema", "")
    }'''
main_content = main_content.replace(old_reps2, new_reps2)

with open(main_path, 'w', encoding='utf-8') as f:
    f.write(main_content)

print("Applied perfect SEO across all files!")
