#!/bin/bash

echo "============================================="
echo "        STREAMHIT AUTOMATIC UPDATE           "
echo "============================================="

cd "$(dirname "$0")"
MAIN_APP_DIR="$(pwd)"
echo "Current directory: $MAIN_APP_DIR"

PYTHON_BIN="/home/lcsyxfen/virtualenv/streamhit.lc-synergy.ltd/3.10/bin/python"
if [ ! -f "$PYTHON_BIN" ]; then
    PYTHON_BIN=$(which python3)
fi

SITEMAP_PIDFILE="/tmp/sitemap_generator.pid"

# Function to generate complete, secure .htaccess with CloudLinux Passenger block
setup_htaccess() {
    target_dir="$1"
    app_name=$(basename "$target_dir")
    echo "Configuring .htaccess for $target_dir ($app_name)..."

    # Determine Python binary for this specific app
    if [ -f "/home/lcsyxfen/virtualenv/$app_name/3.10/bin/python" ]; then
        V_PY="/home/lcsyxfen/virtualenv/$app_name/3.10/bin/python"
    elif [ -f "/home/lcsyxfen/virtualenv/streamhit.lc-synergy.ltd/3.10/bin/python" ]; then
        V_PY="/home/lcsyxfen/virtualenv/streamhit.lc-synergy.ltd/3.10/bin/python"
    else
        V_PY="$PYTHON_BIN"
    fi

    cat << EOF > "$target_dir/.htaccess"
# DO NOT REMOVE. CLOUDLINUX PASSENGER CONFIGURATION BEGIN
PassengerAppRoot "$target_dir"
PassengerBaseURI "/"
PassengerPython "$V_PY"
# DO NOT REMOVE. CLOUDLINUX PASSENGER CONFIGURATION END

# 1. Disable directory browsing
Options -Indexes

# 2. Block direct access to source code, configs, databases, and logs
<FilesMatch "(?i)\.(py|env|json|sql|db|sqlite|sqlite3|sh|log|bat|yml|yaml|gitignore|md|cfg|ini|bak)$">
    Order allow,deny
    Deny from all
</FilesMatch>

# 3. Specifically block critical system files by exact name
<FilesMatch "(?i)^(passenger_wsgi|passenger_wsgi\.py|Procfile|requirements\.txt|singapore_ips\.txt|\.env|\.gitignore|\.scraper_progress\.json|\.last_direct_fail_time|\.db_timezone_migration_done|active_users\.db|facebook_config\.json|tiktok_config\.json|firebase-service-account\.json)$">
    Order allow,deny
    Deny from all
</FilesMatch>

# 4. Block access to hidden files and directories (except .well-known)
<IfModule mod_rewrite.c>
    RewriteEngine On
    RewriteRule (^|/)\.(?!well-known/) - [F,L]
    
    # Block any direct URL query for code / database / config extensions
    RewriteCond %{REQUEST_URI} \.(py|sh|env|sql|db|sqlite|log|json|yml|yaml)$ [NC]
    RewriteRule ^ - [F,L]
</IfModule>

# 5. Security Headers
<IfModule mod_headers.c>
    Header always set X-Content-Type-Options "nosniff"
    Header always set X-Frame-Options "SAMEORIGIN"
    Header always set X-XSS-Protection "1; mode=block"
    Header always set Referrer-Policy "strict-origin-when-cross-origin"
</IfModule>
EOF

    touch "$target_dir/passenger_wsgi.py" 2>/dev/null || true
    mkdir -p "$target_dir/tmp" 2>/dev/null || true
    touch "$target_dir/tmp/restart.txt" 2>/dev/null || true
}

# 1. Pull latest code from GitHub for main app
echo "[1/6] Pulling latest code from origin main..."
git fetch origin
git reset --hard origin/main

# 1.5 Setup .htaccess for main app
setup_htaccess "$MAIN_APP_DIR"

# 2. Ensure all Python dependencies are installed
echo "[2/6] Checking Python dependencies..."
"$PYTHON_BIN" -m pip install -r requirements.txt --quiet || true

# 3. Reset error logs & trigger Passenger reload
echo "[3/6] Resetting error logs & triggering Passenger reload..."
if [ -f "stderr.log" ]; then
    > stderr.log
fi
touch passenger_wsgi.py
mkdir -p tmp
touch tmp/restart.txt

# 4. Sync admin credentials in database
echo "[4/6] Ensuring admin credentials synced in database..."
"$PYTHON_BIN" -c "
import asyncio, os
os.environ['ADMIN_USERNAME'] = 'nxly_master_9'
os.environ['ADMIN_PASSWORD'] = 'NxLy#79Wp$2026!KqX'
os.environ['ADMIN_SECRET_KEY'] = 'nxly_sec_8492'
from main import init_mysql_pool
asyncio.run(init_mysql_pool())
print('Admin database credentials verified successfully.')
" 2>/dev/null || true

# 5. Update other virtual hosts (such as nxly.online)
echo "[5/6] Syncing other Python/Passenger apps (including nxly.online)..."
find /home/lcsyxfen -maxdepth 3 \( -name "main.py" -o -name "passenger_wsgi.py" \) 2>/dev/null | while read -r filepath; do
    appdir=$(dirname "$filepath")
    if [ "$appdir" != "$MAIN_APP_DIR" ]; then
        echo "Updating app at $appdir..."
        if [ -d "$appdir/.git" ]; then
            cd "$appdir"
            git fetch origin 2>/dev/null || true
            git reset --hard origin/main 2>/dev/null || true
            cd "$MAIN_APP_DIR"
        else
            cp -f "$MAIN_APP_DIR"/*.py "$appdir/" 2>/dev/null || true
            mkdir -p "$appdir/public"
            cp -f "$MAIN_APP_DIR"/public/*.html "$appdir/public/" 2>/dev/null || true
            cp -f "$MAIN_APP_DIR"/public/*.css "$appdir/public/" 2>/dev/null || true
            cp -f "$MAIN_APP_DIR"/public/*.js "$appdir/public/" 2>/dev/null || true
        fi
        setup_htaccess "$appdir"
    fi
done

# 6. Sitemaps and app version
echo "[6/6] Updating sitemap cron and mobile app version..."
chmod +x setup_cron.sh 2>/dev/null || true
bash setup_cron.sh 2>/dev/null || true

if [ -f "$SITEMAP_PIDFILE" ] && kill -0 "$(cat $SITEMAP_PIDFILE)" 2>/dev/null; then
    echo "Sitemap generator already running (PID: $(cat $SITEMAP_PIDFILE)). Skipping."
else
    echo "Starting sitemap generator in background..."
    nohup "$PYTHON_BIN" sitemap_generator.py >> sitemap_generator.log 2>&1 &
    echo "Sitemap generator started (PID: $!)"
fi

"$PYTHON_BIN" update_app_version.py 2>/dev/null || true

echo "============================================="
echo "   Update complete! Website has reloaded.     "
echo "============================================="
