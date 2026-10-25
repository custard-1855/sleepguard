#!/bin/bash
# 使い方: sudo ./install.sh
set -e
[ "$(id -u)" = 0 ] || { echo "sudo で実行してください"; exit 1; }
cd "$(dirname "$0")"

PLIST=/Library/LaunchDaemons/com.sleepguard.agent.plist
# /usr/local は Homebrew 環境で一般ユーザーの所有になっていることがあるので使わない
DEST="/Library/Application Support/SleepGuard"
launchctl bootout system "$PLIST" 2>/dev/null || true

install -d -o root -g wheel -m 755 "$DEST"
install -o root -g wheel -m 755 sleepguard.sh "$DEST/sleepguard.sh"
rm -rf /usr/local/sleepguard   # 旧版の配置先
install -o root -g wheel -m 644 com.sleepguard.agent.plist "$PLIST"

launchctl bootstrap system "$PLIST"
echo "インストールしました。ログ: /var/log/sleepguard.log"
