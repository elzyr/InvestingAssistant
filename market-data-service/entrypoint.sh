#!/bin/sh
mkdir -p /app/data
chown -R appuser:appuser /app/data 2>/dev/null || true
exec su -s /bin/sh appuser -c "$*"
