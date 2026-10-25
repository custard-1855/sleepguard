#!/bin/bash
# sleepguard.sh のテスト。使い方: ./test.sh(一般ユーザーで実行する。sudo は付けない)
# SG_DRY_RUN=1 で sleepguard.sh を呼び、ログに出た通知・ダイアログ・ロックを確かめる。

set -u
cd "$(dirname "$0")"
SG=./sleepguard.sh

# root だと sleepguard.sh は SG_* を無視して、本当に通知やロックをしてしまう
(( EUID != 0 )) || { echo "root では実行しないでください" >&2; exit 1; }

for v in $(compgen -v SG_); do unset "$v"; done
export TZ=Asia/Tokyo
ME=$(id -un)

# ログの文言(sleepguard.sh と同じ)
DIALOG="DIALOG: お風呂の時間です"
AWAY="bath: away from Mac, assume done"
MIDNIGHT="NOTIFY: 0時です / 寝る準備をしましょう。"
WARN="NOTIFY: まもなく1時です / 1時以降も使っていると、10分後に画面をロックします。"
LATE="NOTIFY: 1時を過ぎました / 10分後もまだ使っていたら画面をロックします。保存してください。"
LOCK=$'NOTIFY: 画面をロックします / もう寝ましょう。\nlock'
USING=5        # 使用中のアイドル秒数
IDLE=300       # 使っていない(ACTIVE_IDLE 以上、BATH_AWAY 未満)

PASS=0; FAIL=0

at() { date -j -f '%Y-%m-%d %H:%M:%S' "$1:00" +%s; } # at "2026-10-26 22:00" → epoch 秒

begin() { # begin ケース名: 新しい state ディレクトリで始める
  CASE=$1; DIR=$(mktemp -d); NG=""
}

finish() {
  if [ -z "$NG" ]; then
    echo "PASS: $CASE"; PASS=$((PASS + 1))
  else
    echo "FAIL: $CASE"; printf '%s' "$NG"; FAIL=$((FAIL + 1))
  fi
  rm -rf "$DIR"
}

ng() { NG="$NG    $*"$'\n'; }

run() { # run "YYYY-MM-DD HH:MM" アイドル秒 [ユーザー]: 出力(日時を除く)を OUT に入れる
  WHEN=$1
  OUT=$(SG_DRY_RUN=1 SG_STATE_DIR="$DIR" SG_NOW="$(at "$1")" SG_IDLE="$2" SG_USER="${3:-$ME}" \
    "$SG" 2>&1 | cut -d' ' -f3-)
}

expect() { # expect 期待する出力(空なら何も出ないこと)
  [ "$OUT" = "$1" ] || ng "$WHEN: 期待「${1:-(なし)}」 実際「${OUT:-(なし)}」"
}

# run と expect をまとめたもの: step "YYYY-MM-DD HH:MM" アイドル秒 期待する出力 [ユーザー]
step() { run "$1" "$2" "${4:-$ME}"; expect "$3"; }

has() { [ -e "$DIR/$1" ] || ng "$1 がない"; }
hasnt() { [ ! -e "$DIR/$1" ] || ng "$1 がある"; }

# ---------------------------------------------------------------------------
begin "1. 範囲外の時刻(12:00, 21:59, 6:00)では何も起きない"
step "2026-10-26 12:00" $USING ""
step "2026-10-26 21:59" $USING ""
step "2026-10-27 06:00" $USING ""
[ -z "$(ls -A "$DIR")" ] || ng "state ファイルが作られた: $(ls "$DIR")"
finish

begin "2. 22時台に使用中なら、ダイアログが10分おきに出る"
step "2026-10-26 22:00" $USING "$DIALOG"
step "2026-10-26 22:05" $USING ""
step "2026-10-26 22:09" $USING ""
step "2026-10-26 22:10" $USING "$DIALOG"
step "2026-10-26 22:19" $USING ""
step "2026-10-26 22:20" $IDLE  ""          # 使っていなければ出ない
step "2026-10-26 22:21" $USING "$DIALOG"
finish

begin "3. ダイアログのあと15分以上離席すると入浴済みとみなし、以後出ない"
step "2026-10-26 22:00" $USING "$DIALOG"
step "2026-10-26 22:15" 899    ""          # 15分未満の離席ではまだ
step "2026-10-26 22:16" $USING "$DIALOG"
step "2026-10-26 22:31" 900    "$AWAY"
has bath-20261026
step "2026-10-26 22:32" $USING ""
step "2026-10-26 23:59" $USING ""
finish

begin "4. 0時の通知と0:50の予告は、それぞれ一夜に一度だけ出る"
step "2026-10-27 00:00" $USING "$MIDNIGHT"
step "2026-10-27 00:01" $USING ""
step "2026-10-27 00:49" $IDLE  ""
step "2026-10-27 00:50" $USING "$WARN"
step "2026-10-27 00:51" $USING ""
step "2026-10-27 00:59" $IDLE  ""
finish

begin "5. ユーザー不在で通知に失敗したら、ユーザーがいる次の回で通知する"
step "2026-10-27 00:00" 99999  ""          loginwindow
hasnt once-midnight-20261027
step "2026-10-27 00:01" $USING "$MIDNIGHT"
step "2026-10-27 00:02" $USING ""
step "2026-10-27 00:50" 99999  ""          loginwindow
step "2026-10-27 00:51" 99999  ""          root
hasnt once-warn-20261027
step "2026-10-27 00:52" $USING "$WARN"
step "2026-10-27 00:53" $USING ""
finish

begin "6. 1時以降に使用中なら警告し、10分後も使用中ならロック、以後も使用中の各回でロック"
step "2026-10-27 01:00" $USING "$LATE"
step "2026-10-27 01:05" $USING ""
step "2026-10-27 01:09" $USING ""
step "2026-10-27 01:10" $USING "$LOCK"
step "2026-10-27 01:11" $USING "$LOCK"
step "2026-10-27 01:12" $IDLE  ""          # 使っていなければロックしない
step "2026-10-27 01:30" $USING "$LOCK"
step "2026-10-27 05:59" $USING "$LOCK"
step "2026-10-27 06:00" $USING ""
finish

begin "7. 1時以降、使っていなければ何もしない(警告も出ない)"
step "2026-10-27 01:00" $IDLE  ""
step "2026-10-27 01:10" $IDLE  ""
step "2026-10-27 03:00" 99999  ""
hasnt late-20261027
finish

begin "8. root 以外で SG_DRY_RUN か SG_STATE_DIR が欠けていたら exit 1 で何もしない"
# 万一実行されても通知・ロックしないよう、0:00 にユーザー不在で呼ぶ
NOW_ARGS=(SG_NOW="$(at "2026-10-27 00:00")" SG_USER=loginwindow SG_IDLE=99999)
for vars in "SG_STATE_DIR=$DIR" "SG_DRY_RUN=1" "SG_DRY_RUN=1 SG_STATE_DIR=" \
            "SG_DRY_RUN=0 SG_STATE_DIR=$DIR" "SG_DRY_RUN=yes SG_STATE_DIR=$DIR" ""; do
  WHEN="[${vars:-SG_* なし}]"
  err=$(mktemp)
  # shellcheck disable=SC2086 # vars は空白区切りの VAR=値 の並び
  OUT=$(env "${NOW_ARGS[@]}" $vars "$SG" 2>"$err"); rc=$?
  [ "$rc" = 1 ] || ng "$WHEN: 終了コードが 1 でない($rc)"
  [ -s "$err" ] || ng "$WHEN: stderr にメッセージがない"
  expect ""
  rm -f "$err"
done
[ -z "$(ls -A "$DIR")" ] || ng "state ファイルが作られた: $(ls "$DIR")"
finish

begin "9. 日付が変わっても state ファイルは日ごとに分かれ、前夜の分が翌日に影響しない"
# 1夜目: 入浴済みになり、0時の通知・予告・ロックまで済ませる
step "2026-10-26 22:00" $USING "$DIALOG"
step "2026-10-26 22:20" 900    "$AWAY"
step "2026-10-26 23:59" $USING ""
step "2026-10-27 00:00" $USING "$MIDNIGHT"
step "2026-10-27 00:50" $USING "$WARN"
step "2026-10-27 01:00" $USING "$LATE"
step "2026-10-27 01:10" $USING "$LOCK"
has bath-20261026; has last-bath-20261026
has once-midnight-20261027; has once-warn-20261027; has late-20261027
hasnt bath-20261027; hasnt once-midnight-20261026
# 2夜目: すべて最初からやり直しになる
step "2026-10-27 22:00" $USING "$DIALOG"
step "2026-10-27 23:59" $USING "$DIALOG"
step "2026-10-28 00:00" $USING "$MIDNIGHT"
step "2026-10-28 00:50" $USING "$WARN"
step "2026-10-28 01:00" $USING "$LATE"
step "2026-10-28 01:05" $USING ""
step "2026-10-28 01:10" $USING "$LOCK"
finish

begin "10. 古い state ファイルの削除は、直下のスクリプトが作るファイルだけ"
mkdir "$DIR/sub"
for f in once-old bath-old last-bath-old late-old other.txt sub/once-old; do
  touch -t 202001010000 "$DIR/$f"
done
step "2026-10-26 22:00" $IDLE ""
hasnt once-old; hasnt bath-old; hasnt last-bath-old; hasnt late-old
has other.txt; has sub/once-old
finish

# ---------------------------------------------------------------------------
echo "----"
echo "PASS: $PASS  FAIL: $FAIL"
[ "$FAIL" = 0 ]
