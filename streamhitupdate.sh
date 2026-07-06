#!/bin/bash

# Print message
echo "============================================="
echo "        STREAMHIT AUTOMATIC UPDATE           "
echo "============================================="

# Navigate to the script's directory (app root)
cd "$(dirname "$0")"
echo "Current directory: $(pwd)"

# 1. Pull latest code from GitHub
echo "[1/4] Pulling latest code from origin main..."
git fetch origin
git reset --hard origin/main

# # 2. Stop running python processes
# echo "[2/4] Killing active Python and WSGI processes..."
# pkill -9 -u $(whoami) -f python 2>/dev/null
# pkill -9 -u $(whoami) -f lswsgi 2>/dev/null

# 3. Clear stderr logs and touch passenger_wsgi.py to restart the passenger WSGI server
echo "[3/4] Resetting error logs & triggering Passenger reload..."
if [ -f "stderr.log" ]; then
    > stderr.log
fi
touch passenger_wsgi.py
mkdir -p tmp
touch tmp/restart.txt

# 4. Start the background scraper using the specific virtualenv path
# echo "[4/4] Starting scraper in background..."
# nohup env RUN_SCRAPER=true /home/lcsyxfen/virtualenv/streamhit.lc-synergy.ltd/3.10/bin/python main.py >> scraper.log 2>&1 &

echo "Scraper logs are being written to scraper.log"

echo "============================================="
echo "   Update complete! Website has reloaded.     "
echo "============================================="
