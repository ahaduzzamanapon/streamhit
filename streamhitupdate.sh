#!/bin/bash

# Print message
echo "============================================="
echo "        STREAMHIT AUTOMATIC UPDATE           "
echo "============================================="

# Navigate to the script's directory (app root)
cd "$(dirname "$0")"
echo "Current directory: $(pwd)"

PYTHON_BIN="/home/lcsyxfen/virtualenv/streamhit.lc-synergy.ltd/3.10/bin/python"

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

# 4. Run sitemap generator immediately in background
echo "[4/4] Starting sitemap generator in background..."
nohup "$PYTHON_BIN" sitemap_generator.py >> sitemap_generator.log 2>&1 &
echo "Sitemap generator started (PID: $!)"

echo "============================================="
echo "   Update complete! Website has reloaded.     "
echo "============================================="
