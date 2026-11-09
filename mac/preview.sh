#!/bin/bash
# 使い方: ./preview.sh [H:MM] [--quick] [--text "文"]
# 全画面の表示を、install せずに手元で確かめる(一般ユーザーで実行する)。
#   H:MM     ~/.config/sleepguard/messages.md のこの見出しの文面を出す(省略時は 22:30)。
#            0:00 より前なら入浴の時間帯(「入浴済み」ボタンあり)、0:00 以降なら就寝の時間帯
#   --quick  15秒・30秒・2分の待ち時間を 2秒・4秒・20秒に、入浴の画面の離席判定を 15分から 10秒に縮める
#   --text   ファイルの代わりにこの文を出す(ファイルに書く前に長さを確かめるとき)
# 出した文の記録は書かない。それ以外は本番と同じ見た目と挙動。
# バイナリは .build/ に置き、SleepGuardShow.swift がバイナリより新しいときだけコンパイルし直す。
set -eu
cd "$(dirname "$0")"
SRC=SleepGuardShow.swift
BIN=.build/sleepguard-show

command -v swiftc >/dev/null 2>&1 || {
  echo "swiftc がありません。xcode-select --install で Command Line Tools を入れてください" >&2; exit 1; }
if [ ! -x "$BIN" ] || [ "$SRC" -nt "$BIN" ]; then
  mkdir -p .build
  echo "コンパイルしています..." >&2
  swiftc -O -o "$BIN" "$SRC"
fi

band=22:30
args=()
while (( $# )); do
  case $1 in
    --quick) args+=(--quick) ;;
    --text)  [ $# -ge 2 ] || { echo "--text には文を続けてください" >&2; exit 1; }
             args+=(--text "$2"); shift ;;
    --help|-h) sed -n '2,10p' "$0"; exit 0 ;;
    *) band=$1 ;;
  esac
  shift
done
[[ $band =~ ^([0-9]{1,2}):([0-9]{2})$ ]] || { echo "時刻は H:MM で指定してください: $band" >&2; exit 1; }
hh=$((10#${BASH_REMATCH[1]})); mm=$((10#${BASH_REMATCH[2]}))
(( hh < 24 && mm < 60 )) || { echo "時刻が範囲外です: $band" >&2; exit 1; }
kind=sleep; (( hh >= 12 )) && kind=bath   # sleepguard.sh と同じ: 正午以降〜0:00 より前に始まる時間帯が入浴

exec "$BIN" --kind "$kind" --band "$band" --no-record ${args[@]+"${args[@]}"}
