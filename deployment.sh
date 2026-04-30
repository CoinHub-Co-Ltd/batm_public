#!/bin/bash

set -euo pipefail

HOST="CoinHubDigitalOceanCASGB"

EXT_JAR_LOCAL="server_extensions_extra/build/libs/batm_server_extensions_extra.jar"
CUR_JAR_LOCAL="currencies/build/libs/currencies-1.17.6.jar"

echo "Ì∫Ä Starting deployment..."

# Validate files
[ -f "$EXT_JAR_LOCAL" ] || { echo "‚ùå Extension JAR not found"; exit 1; }
[ -f "$CUR_JAR_LOCAL" ] || { echo "‚ùå Currencies JAR not found"; exit 1; }

echo "Ì≥§ Uploading JAR files..."
scp "$EXT_JAR_LOCAL" "$HOST:/home/cas/"
scp "$CUR_JAR_LOCAL" "$HOST:/home/cas/"

echo "Ì¥ß Running remote commands..."

ssh "$HOST" << 'EOF'

set -e

echo "Ì¥ê Switching to root..."
sudo bash << 'INNER'

set -e

echo "Ì≥¶ Renaming extension..."
mv /home/cas/batm_server_extensions_extra.jar /home/cas/server_extensions_extra-1.16.13.jar

echo "Ì≤æ Backing up old files..."
cp /batm/app/master/extensions/server_extensions_extra-1.16.13.jar /batm/app/master/extensions/server_extensions_extra-1.16.13.jar.bak || true
cp /batm/app/master/lib/currencies-1.17.6.jar /batm/app/master/lib/currencies-1.17.6.jar.bak || true

echo "Ì≥Ç Moving new files..."
mv /home/cas/server_extensions_extra-1.16.13.jar /batm/app/master/extensions/
mv /home/cas/currencies-1.17.6.jar /batm/app/master/lib/

echo "Ì¥Ñ Restarting BATM..."
cd /batm
./batm-manage stop all
./batm-manage start all

echo "‚úÖ Deployment completed!"

INNER

EOF

echo "Ìæâ Done!"
