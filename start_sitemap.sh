#!/bin/bash
pkill -9 -f sitemap_generator.py 2>/dev/null
sleep 2
cd /home/lcsyxfen/streamhit.lc-synergy.ltd
PYTHONUNBUFFERED=1 nohup /home/lcsyxfen/virtualenv/streamhit.lc-synergy.ltd/3.10/bin/python -u sitemap_generator.py > sitemap_generator.log 2>&1 &
echo $! > /tmp/sitemap_generator.pid
echo "Started PID: $(cat /tmp/sitemap_generator.pid)"
sleep 10
tail -8 sitemap_generator.log
