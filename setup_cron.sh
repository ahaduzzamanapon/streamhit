#!/bin/bash

# This script safely adds/updates background cron jobs for sitemap & autopilot.
# It is called automatically from streamhitupdate.sh on every deploy.

PYTHON_BIN="/home/lcsyxfen/virtualenv/streamhit.lc-synergy.ltd/3.10/bin/python"
APP_DIR="/home/lcsyxfen/streamhit.lc-synergy.ltd"

SITEMAP_CRON="0 1 * * * cd $APP_DIR && $PYTHON_BIN sitemap_generator.py >> sitemap_generator.log 2>&1"
AUTOPILOT_CRON="*/10 * * * * /usr/bin/flock -n /tmp/autopilot_cron.lock $PYTHON_BIN $APP_DIR/autopilot_cron.py >> /tmp/autopilot_cron.log 2>&1"

# Remove old entries and install clean cron entries
( crontab -l 2>/dev/null | grep -v "sitemap_generator" | grep -v "autopilot_cron" ; echo "$SITEMAP_CRON" ; echo "$AUTOPILOT_CRON" ) | crontab -

echo "Cron jobs scheduled successfully:"
crontab -l
