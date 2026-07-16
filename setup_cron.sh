#!/bin/bash

# This script safely adds/updates the sitemap cron job.
# It is called automatically from streamhitupdate.sh on every deploy.

PYTHON_BIN="/home/lcsyxfen/virtualenv/streamhit.lc-synergy.ltd/3.10/bin/python"
APP_DIR="/home/lcsyxfen/streamhit.lc-synergy.ltd"
CRON_JOB="0 1 * * * cd $APP_DIR && $PYTHON_BIN sitemap_generator.py >> sitemap_generator.log 2>&1"

# Remove old sitemap cron entry if exists, then add fresh one
( crontab -l 2>/dev/null | grep -v "sitemap_generator" ; echo "$CRON_JOB" ) | crontab -

echo "Sitemap cron job scheduled: every day at 01:00 AM"
echo "Current crontab:"
crontab -l
