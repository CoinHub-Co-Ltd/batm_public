#!/bin/bash

set -euo pipefail

HOST="CoinHubDigitalOceanCASGB"

EXT_JAR_LOCAL="server_extensions_extra/build/libs/batm_server_extensions_extra.jar"
CUR_JAR_LOCAL="currencies/build/libs/currencies-1.17.6.jar"

echo "Starting deployment..."

# Validate files
[ -f "$EXT_JAR_LOCAL" ] || { echo "Extension JAR not found"; exit 1; }
[ -f "$CUR_JAR_LOCAL" ] || { echo "Currencies JAR not found"; exit 1; }

echo "Uploading JAR files..."
scp "$EXT_JAR_LOCAL" "$HOST:/home/cas/"
scp "$CUR_JAR_LOCAL" "$HOST:/home/cas/"

echo "Running remote commands..."

ssh "$HOST" << 'EOF'

set -e

echo "Switching to root..."
sudo bash << 'INNER'

set -e

echo "Renaming extension..."
mv /home/cas/batm_server_extensions_extra.jar /home/cas/server_extensions_extra-1.16.13.jar

echo "Backing up old files..."
cp /batm/app/master/extensions/server_extensions_extra-1.16.13.jar /batm/app/master/extensions/server_extensions_extra-1.16.13.jar.bak || true
cp /batm/app/master/lib/currencies-1.17.6.jar /batm/app/master/lib/currencies-1.17.6.jar.bak || true

echo "Moving new files..."
mv /home/cas/server_extensions_extra-1.16.13.jar /batm/app/master/extensions/
mv /home/cas/currencies-1.17.6.jar /batm/app/master/lib/

echo "Restarting BATM..."
cd /batm
./batm-manage stop all
./batm-manage start all

echo "Deployment completed!"

INNER

EOF

echo "Done!"
