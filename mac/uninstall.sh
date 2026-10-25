#!/bin/bash
# 使い方: sudo ./uninstall.sh
[ "$(id -u)" = 0 ] || { echo "sudo で実行してください"; exit 1; }
PLIST=/Library/LaunchDaemons/com.sleepguard.agent.plist
launchctl bootout system "$PLIST" 2>/dev/null
pfctl -a com.apple/sleepguard -F rules 2>/dev/null   # 旧版のネット遮断ルールが残っていれば消す
rm -f "$PLIST"
rm -rf "/Library/Application Support/SleepGuard" /usr/local/sleepguard /var/db/sleepguard
echo "アンインストールしました"
