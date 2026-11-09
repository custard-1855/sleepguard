#!/bin/bash
# 使い方: sudo ./install.sh
set -e
[ "$(id -u)" = 0 ] || { echo "sudo で実行してください"; exit 1; }
cd "$(dirname "$0")"

PLIST=/Library/LaunchDaemons/com.sleepguard.agent.plist
# /usr/local は Homebrew 環境で一般ユーザーの所有になっていることがあるので使わない
DEST="/Library/Application Support/SleepGuard"
# sudo した人(文面ファイルを置く相手。コンパイルもこの人の権限で行う)
USER_NAME=${SUDO_USER:-}
as_user() { if [ -n "$USER_NAME" ]; then sudo -H -u "$USER_NAME" "$@"; else "$@"; fi; }

command -v swiftc >/dev/null 2>&1 || {
  echo "swiftc がありません。xcode-select --install で Command Line Tools を入れてから、もう一度実行してください"; exit 1; }

# 全画面アプリをコンパイルする(初回は数十秒かかる)
BUILD=$(as_user mktemp -d)
echo "全画面アプリをコンパイルしています..."
as_user swiftc -O -o "$BUILD/sleepguard-show" SleepGuardShow.swift

launchctl bootout system "$PLIST" 2>/dev/null || true

install -d -o root -g wheel -m 755 "$DEST"
install -o root -g wheel -m 755 sleepguard.sh "$DEST/sleepguard.sh"
install -o root -g wheel -m 755 "$BUILD/sleepguard-show" "$DEST/sleepguard-show"
rm -rf "$BUILD"
rm -rf /usr/local/sleepguard   # 旧版の配置先
install -o root -g wheel -m 644 com.sleepguard.agent.plist "$PLIST"

# 文面ファイル: まだ無いときだけ置く(編集した文面は上書きしない)
if [ -n "$USER_NAME" ]; then
  HOME_DIR=$(dscl . -read "/Users/$USER_NAME" NFSHomeDirectory | awk '{print $2; exit}')
  CFG="$HOME_DIR/.config/sleepguard/messages.md"
  if [ ! -e "$CFG" ]; then
    as_user mkdir -p "$(dirname "$CFG")"
    as_user cp messages.md "$CFG"
    echo "文面ファイルを置きました: $CFG"
  else
    echo "文面ファイルはそのままです: $CFG"
  fi
else
  echo "注意: sudo 経由でないので文面ファイルは置きません。~/.config/sleepguard/messages.md に messages.md をコピーしてください"
fi

launchctl bootstrap system "$PLIST"
echo "インストールしました。ログ: /var/log/sleepguard.log"
