#!/bin/bash

echo "============================================="
echo "        STREAMHIT AUTOMATIC UPDATE           "
echo "============================================="

cd "$(dirname "$0")"
echo "Current directory: $(pwd)"

PYTHON_BIN="/home/lcsyxfen/virtualenv/streamhit.lc-synergy.ltd/3.10/bin/python"
SITEMAP_PIDFILE="/tmp/sitemap_generator.pid"

# 1. Pull latest code from GitHub
echo "[1/4] Pulling latest code from origin main..."
git fetch origin
git reset --hard origin/main

# 2. Reset error logs & trigger Passenger reload
echo "[2/4] Resetting error logs & triggering Passenger reload..."
if [ -f "stderr.log" ]; then
    > stderr.log
fi
touch passenger_wsgi.py
mkdir -p tmp
touch tmp/restart.txt

# 3. Setup cron job for daily sitemap regeneration at 1 AM
echo "[3/4] Setting up daily sitemap cron job..."
chmod +x setup_cron.sh
bash setup_cron.sh

# 4. Run sitemap generator (lock file prevents duplicates)
echo "[4/4] Checking sitemap generator..."
if [ -f "$SITEMAP_PIDFILE" ] && kill -0 "$(cat $SITEMAP_PIDFILE)" 2>/dev/null; then
    echo "Sitemap generator already running (PID: $(cat $SITEMAP_PIDFILE)). Skipping."
else
    echo "Starting sitemap generator in background..."
    nohup "$PYTHON_BIN" sitemap_generator.py >> sitemap_generator.log 2>&1 &
    echo $! > "$SITEMAP_PIDFILE"
    echo "Sitemap generator started (PID: $!)"
fi

echo "============================================="
echo "   Update complete! Website has reloaded.     "
echo "============================================="
