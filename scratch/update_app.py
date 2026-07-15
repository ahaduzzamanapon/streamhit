import re

with open(r'E:\P\movie bot\public\app.js', 'r', encoding='utf-8') as f:
    content = f.read()

# Replace createContentCard
old_card = '''function createContentCard(item) {
    const card = document.createElement("div");
    card.className = "content-card";
    card.dataset.id = item.subjectId;
    card.onclick = () => window.location.href = `/details?id=${item.subjectId}&path=${encodeURIComponent(item.detailPath || '')}`;'''

new_card = '''function createContentCard(item) {
    const card = document.createElement("a");
    card.className = "content-card";
    card.dataset.id = item.subjectId;
    const pathValue = item.detailPath || '';
    card.href = (item.subjectType === 2 ? '/tv/' : '/movie/') + encodeURIComponent(pathValue);
    card.style.textDecoration = 'none';'''

content = content.replace(old_card, new_card)

# Replace slider button onclicks
content = re.sub(r'onclick="window\.location\.href=\'/details\?id=\$\{item\.subjectId\}&path=\$\{encodeURIComponent\(item\.detailPath \|\| sub\.detailPath \|\| \'\'\)\}\'"', r'onclick="window.location.href=\'/\' + (item.subjectType === 2 ? \'tv\' : \'movie\') + \'/\' + encodeURIComponent(item.detailPath || sub.detailPath || \'\')"', content)
content = re.sub(r'window\.location\.href = `/details\?id=\$\{item\.subjectId\}&path=\$\{encodeURIComponent\(item\.detailPath\)\}`;', r"window.location.href = '/' + (item.subjectType === 2 ? 'tv' : 'movie') + '/' + encodeURIComponent(item.detailPath || '');", content)

# Replace pushState and noti redirects
content = re.sub(r'window\.location\.href = `/details\?id=\$\{noti\.subjectId\}`;', r"window.location.href = '/movie/' + (noti.detailPath || 'unknown');", content)

# Replace initPage routing
old_init1 = '''if (path.includes("/details")) {
        loadDetails();'''
new_init1 = '''if (path.match(/^\/(movie|tv)\/[^\/]+$/)) {
        loadDetails();'''
content = content.replace(old_init1, new_init1)

old_init2 = '''} else if (path.includes("/watch")) {
        loadWatch();'''
new_init2 = '''} else if (path.match(/^\/watch\/(movie|tv)\/[^\/]+$/)) {
        loadWatch();'''
content = content.replace(old_init2, new_init2)

# In loadDetails and loadWatch, extract slug from pathname instead of URLSearchParams
# Replaces exactly this:
# const urlParams = new URLSearchParams(window.location.search);
# const subjectId = urlParams.get("id");
content = re.sub(r'const urlParams = new URLSearchParams\(window\.location\.search\);\s*const subjectId = urlParams\.get\(\"id\"\);', r'const pathParts = window.location.pathname.split("/");\n    const detailPath = pathParts[pathParts.length - 1];\n    const subjectId = "";', content)

# Fix API calls to use detailPath instead of id
content = re.sub(r'`/api/detail\?subjectId=\$\{subjectId\}&detailPath=\$\{encodeURIComponent\(detailPath\)\}`', r'`/api/detail?detailPath=${encodeURIComponent(detailPath)}`', content)
content = re.sub(r'`/api/season-info\?subjectId=\$\{subjectId\}&detailPath=\$\{encodeURIComponent\(detailPath\)\}`', r'`/api/season-info?detailPath=${encodeURIComponent(detailPath)}`', content)
content = re.sub(r'`/api/resource\?subjectId=\$\{subjectId\}&se=\$\{se\}&ep=\$\{ep\}&detailPath=\$\{encodeURIComponent\(detailPath\)\}`', r'`/api/resource?se=${se}&ep=${ep}&detailPath=${encodeURIComponent(detailPath)}`', content)
content = re.sub(r'`/api/captions\?subjectId=\$\{subjectId\}&se=\$\{se\}&ep=\$\{ep\}&detailPath=\$\{encodeURIComponent\(detailPath\)\}`', r'`/api/captions?se=${se}&ep=${ep}&detailPath=${encodeURIComponent(detailPath)}`', content)

# Remove `new URLSearchParams... get('path')` everywhere
content = re.sub(r'new URLSearchParams\(window\.location\.search\)\.get\("path"\)', r'window.location.pathname.split("/").pop()', content)

# Fix `loadWatch` parameter passing
content = content.replace("window.location.href = `/watch?id=${subjectId}&path=${encodeURIComponent(detailPath)}`;", "const type = window.location.pathname.includes('/tv/') ? 'tv' : 'movie'; window.location.href = `/watch/${type}/${encodeURIComponent(detailPath)}`;")

with open(r'E:\P\movie bot\public\app.js', 'w', encoding='utf-8') as f:
    f.write(content)
print('Updated app.js successfully!')
