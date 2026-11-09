#!/bin/bash
# sleepguard.sh のテスト。使い方: ./test.sh(一般ユーザーで実行する。sudo は付けない)
# SG_DRY_RUN=1 で sleepguard.sh を呼び、ログに出た表示(SHOW)と入浴の判定を確かめる。
# 全画面アプリは起動しない。アプリの見た目は ./preview.sh で確かめる。

set -u
cd "$(dirname "$0")"
SG=./sleepguard.sh

# root だと sleepguard.sh は SG_* を無視して、本当に表示してしまう
(( EUID != 0 )) || { echo "root では実行しないでください" >&2; exit 1; }

for v in $(compgen -v SG_); do unset "$v"; done
export TZ=Asia/Tokyo
ME=$(id -un)

# 文面ファイル(スクリプトが読むのは見出しの時刻だけ)
MSG=$(mktemp)
default_messages() {
  cat > "$MSG" <<'MD'
## 22:30に表示
- お風呂に入ろう
## 0:00以降に表示
- もう寝ましょう
MD
}

# ログの文言(sleepguard.sh と同じ)
BATH="SHOW: bath 22:30"
SLEEP="SHOW: sleep 0:00"
AWAY="bath: away from Mac, assume done"
BUTTON=$'SHOW: bath 22:30\nbath done (button)'
LEFT=$'SHOW: bath 22:30\nbath: away from Mac, assume done'
USING=5        # 使用中のアイドル秒数
IDLE=300       # 使っていない(ACTIVE_IDLE 以上、BATH_AWAY 未満)
SHOW_RC=0      # 表示アプリの終了コードのつもり(2 = 入浴済みを押した、3 = 表示中に離席した)

PASS=0; FAIL=0

at() { date -j -f '%Y-%m-%d %H:%M:%S' "$1:00" +%s; } # at "2026-10-26 22:00" → epoch 秒

begin() { # begin ケース名: 新しい state ディレクトリと既定の文面ファイルで始める
  CASE=$1; DIR=$(mktemp -d); NG=""; SHOW_RC=0; default_messages
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
    SG_MESSAGES="$MSG" SG_SHOW_RC="$SHOW_RC" "$SG" 2>&1 | cut -d' ' -f3-)
}

expect() { # expect 期待する出力(空なら何も出ないこと)
  [ "$OUT" = "$1" ] || ng "$WHEN: 期待「${1:-(なし)}」 実際「${OUT:-(なし)}」"
}

# run と expect をまとめたもの: step "YYYY-MM-DD HH:MM" アイドル秒 期待する出力 [ユーザー]
step() { run "$1" "$2" "${4:-$ME}"; expect "$3"; }

has() { [ -e "$DIR/$1" ] || ng "$1 がない"; }
hasnt() { [ ! -e "$DIR/$1" ] || ng "$1 がある"; }
empty_state() { [ -z "$(ls -A "$DIR")" ] || ng "state ファイルが作られた: $(ls "$DIR")"; }

# ---------------------------------------------------------------------------
begin "1. 時間帯の外(12:00, 21:59, 最初の見出しより前の 22:29, 6:00)では何も起きない"
step "2026-10-26 12:00" $USING ""
step "2026-10-26 21:59" $USING ""
step "2026-10-26 22:29" $USING ""
step "2026-10-27 06:00" $USING ""
empty_state
finish

begin "2. 22:30 から使用中なら、入浴の文面が10分おきに出る"
step "2026-10-26 22:30" $USING "$BATH"
step "2026-10-26 22:35" $USING ""
step "2026-10-26 22:39" $USING ""
step "2026-10-26 22:40" $USING "$BATH"
step "2026-10-26 22:49" $USING ""
step "2026-10-26 22:50" $IDLE  ""          # 使っていなければ出ない
step "2026-10-26 22:51" $USING "$BATH"
step "2026-10-26 23:59" $USING "$BATH"
finish

begin "3. 表示のあと15分以上離席すると入浴済みとみなし、以後出ない"
step "2026-10-26 22:30" $USING "$BATH"
step "2026-10-26 22:45" 899    ""          # 15分未満の離席ではまだ
step "2026-10-26 22:46" $USING "$BATH"
step "2026-10-26 23:01" 900    "$AWAY"
has bath-20261026
step "2026-10-26 23:02" $USING ""
step "2026-10-26 23:59" $USING ""
finish

begin "4. 「入浴済み」を押したら(終了コード2)入浴済みになり、以後出ない"
SHOW_RC=2
step "2026-10-26 22:30" $USING "$BUTTON"
has bath-20261026
SHOW_RC=0
step "2026-10-26 22:40" $USING ""
step "2026-10-26 23:59" $USING ""
finish

begin "4b. 表示中に離席して画面が自分で閉じたら(終了コード3)入浴済みになり、以後出ない"
SHOW_RC=3
step "2026-10-26 22:30" $USING "$LEFT"
has bath-20261026
SHOW_RC=0
step "2026-10-26 22:40" $USING ""
step "2026-10-26 23:59" $USING ""
finish

begin "5. ユーザーがいなければ何もせず、state も作らない。ユーザーがいる次の回で出す"
step "2026-10-26 22:30" $USING ""          loginwindow
step "2026-10-26 22:31" $USING ""          root
empty_state
step "2026-10-26 22:32" $USING "$BATH"
step "2026-10-27 00:00" $USING ""          loginwindow
hasnt last-sleep-20261026
step "2026-10-27 00:01" $USING "$SLEEP"
finish

begin "6. 0:00 から使用中なら就寝の文面が15分おき、1時以降は10分おき、6時で終わる"
step "2026-10-27 00:00" $USING "$SLEEP"
step "2026-10-27 00:05" $USING ""
step "2026-10-27 00:14" $USING ""
step "2026-10-27 00:15" $USING "$SLEEP"
step "2026-10-27 00:29" $IDLE  ""          # 使っていなければ出ない
step "2026-10-27 00:30" $USING "$SLEEP"
step "2026-10-27 00:59" $USING "$SLEEP"
step "2026-10-27 01:00" $USING ""          # 0:59 から1分しかたっていない
step "2026-10-27 01:08" $USING ""
step "2026-10-27 01:09" $USING "$SLEEP"    # 1時以降は10分おき
step "2026-10-27 01:14" $USING ""
step "2026-10-27 01:19" $USING "$SLEEP"
step "2026-10-27 05:59" $USING "$SLEEP"
step "2026-10-27 06:00" $USING ""
finish

begin "7. 0時以降、使っていなければ何もしない"
step "2026-10-27 00:00" $IDLE  ""
step "2026-10-27 01:00" $IDLE  ""
step "2026-10-27 03:00" 99999  ""
hasnt last-sleep-20261026
finish

begin "8. root 以外で SG_DRY_RUN か SG_STATE_DIR が欠けていたら exit 1 で何もしない"
# 万一実行されても表示しないよう、0:00 にユーザー不在で呼ぶ
NOW_ARGS=(SG_NOW="$(at "2026-10-27 00:00")" SG_USER=loginwindow SG_IDLE=99999 SG_MESSAGES="$MSG")
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
empty_state
finish

begin "9. state ファイルは夜ごとに分かれ、前夜の分が翌日に影響しない"
# 1夜目: 入浴済みになり、就寝の文面も出す
step "2026-10-26 22:30" $USING "$BATH"
step "2026-10-26 22:50" 900    "$AWAY"
step "2026-10-26 23:59" $USING ""
step "2026-10-27 00:00" $USING "$SLEEP"
step "2026-10-27 01:00" $USING "$SLEEP"
has bath-20261026; has last-bath-20261026; has last-sleep-20261026
hasnt bath-20261027; hasnt last-sleep-20261027
# 2夜目: すべて最初からやり直しになる
step "2026-10-27 22:30" $USING "$BATH"
step "2026-10-27 23:59" $USING "$BATH"
step "2026-10-28 00:00" $USING "$SLEEP"
step "2026-10-28 00:10" $USING ""
step "2026-10-28 00:15" $USING "$SLEEP"
has last-sleep-20261027
finish

begin "10. 古い state ファイルの削除は、直下のスクリプトが作るファイルだけ"
mkdir "$DIR/sub"
for f in once-old bath-old last-bath-old last-sleep-old late-old other.txt sub/once-old; do
  touch -t 202001010000 "$DIR/$f"
done
step "2026-10-26 22:30" $IDLE ""
hasnt once-old; hasnt bath-old; hasnt last-bath-old; hasnt last-sleep-old; hasnt late-old
has other.txt; has sub/once-old
finish

begin "11. 時間帯は見出しの時刻で決まる。時刻でない見出しや範囲外の時刻は無視する"
cat > "$MSG" <<'MD'
# SleepGuard の文面
## メモ
- これは見出しが時刻ではないので無視
## 25:00
## 3:70
## 1:5
## 2:00 就寝
- 寝よう
## 23:00 入浴
- お風呂
MD
step "2026-10-26 22:30" $USING ""
step "2026-10-26 22:59" $USING ""
step "2026-10-26 23:00" $USING "SHOW: bath 23:00"
step "2026-10-27 00:30" $USING "SHOW: bath 23:00"   # 入浴の時間帯は 2:00 まで続き、日付が変わっても入浴の判定が続く
step "2026-10-27 00:45" 900    "$AWAY"
has bath-20261026
step "2026-10-27 01:59" $USING ""
step "2026-10-27 02:00" $USING "SHOW: sleep 2:00"
step "2026-10-27 02:09" $USING ""
step "2026-10-27 02:10" $USING "SHOW: sleep 2:00"
step "2026-10-27 05:59" $USING "SHOW: sleep 2:00"
step "2026-10-27 06:00" $USING ""
finish

begin "12. 0:00 より前の見出しが無ければ入浴の表示は出ず、0:00 から就寝の表示だけ出る"
printf '## 0:00\n- 寝よう\n' > "$MSG"
step "2026-10-26 22:30" $USING ""
step "2026-10-26 23:59" $USING ""
empty_state
step "2026-10-27 00:00" $USING "$SLEEP"
hasnt bath-20261026; hasnt last-bath-20261026
finish

begin "13. 文面ファイルが無いか、見出しが無ければ何もしない"
rm -f "$MSG"
step "2026-10-26 22:30" $USING ""
step "2026-10-27 01:00" $USING ""
printf -- '- 見出しのない文\n' > "$MSG"
step "2026-10-26 22:30" $USING ""
step "2026-10-27 01:00" $USING ""
empty_state
finish

# ---------------------------------------------------------------------------
rm -f "$MSG"
echo "----"
echo "PASS: $PASS  FAIL: $FAIL"
[ "$FAIL" = 0 ]
