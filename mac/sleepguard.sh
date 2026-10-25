#!/bin/bash
# SleepGuard for macOS (通知+入浴ダイアログ・音なし・ネット遮断なし)
#   22:00-23:59  Macを使っている間、10分おきに入浴ダイアログ(「入浴済み」で終了)
#                ダイアログ後に15分以上Macを離れた場合も入浴済みとみなす
#   00:00        通知「寝る準備」
#   00:50        予告「1時以降も使っていると画面をロックする」
#   01:00-05:59  操作中なら → 通知 → 10分後も操作中なら画面ロック(以後、操作するたびロック)
#                操作していなければ何もしない
# 時刻はMacのタイムゾーン設定(現地時間)で判定する。
# 画面ロックは画面を消すだけで、スリープはしない(実行中の処理は止まらない)。
# launchd(root)から22:00〜05:59の間だけ毎分実行される。

set -u
PATH=/usr/bin:/bin:/usr/sbin:/sbin

# ===== 設定 (時刻は HHMM を数値で。先頭の0は付けない) =====
BATH_START=2200; BATH_END=2359
BATH_INTERVAL=600          # 入浴ダイアログの間隔(秒)
BATH_AWAY=900              # ダイアログ後これだけ離席したら入浴済みとみなす(秒)
MIDNIGHT_START=0; MIDNIGHT_END=49
WARN_START=50;   WARN_END=59
LATE_START=100;  LATE_END=559
ACTIVE_IDLE=120            # 無操作がこの秒数未満なら「使用中」とみなす
LOCK_GRACE=600             # 1時以降の通知からこの秒数たっても使用中なら画面ロック
# ============================================================

# root(launchd から起動)のときは SG_* をすべて無視する。
# 一般ユーザーのときは、テスト用に SG_DRY_RUN=1 と SG_STATE_DIR を指定した場合だけ動く。
if (( EUID == 0 )); then
  STATE_DIR=/var/db/sleepguard
  DRY_RUN=0
  NOW=$(date +%s)
  CU=$(stat -f %Su /dev/console 2>/dev/null)
  IDLE_OVERRIDE=
else
  if [ "${SG_DRY_RUN:-}" != 1 ] || [ -z "${SG_STATE_DIR:-}" ]; then
    echo "sleepguard: root 以外で実行するときは SG_DRY_RUN=1 と SG_STATE_DIR の指定が必要です" >&2
    exit 1
  fi
  STATE_DIR=$SG_STATE_DIR
  DRY_RUN=1
  NOW=${SG_NOW:-$(date +%s)}
  CU=${SG_USER:-$(stat -f %Su /dev/console 2>/dev/null)}
  IDLE_OVERRIDE=${SG_IDLE:-}
fi

log() { echo "$(date '+%F %T') $*"; }
fmt() { date -r "$NOW" "+$1" 2>/dev/null || date -d "@$NOW" "+$1"; }
read -r HM DAY < <(fmt '%H%M %Y%m%d')
HM=$((10#$HM))

# 対象時間帯(BATH_START〜翌LATE_END)の外では何もしない
(( HM >= BATH_START || HM <= LATE_END )) || exit 0

mkdir -p "$STATE_DIR"

# ---- 使用状況 ----
CUID=$(id -u "$CU" 2>/dev/null || echo "")
idle_secs() {
  if [ -n "$IDLE_OVERRIDE" ]; then echo "$IDLE_OVERRIDE"; return; fi
  ioreg -r -d 1 -k HIDIdleTime -c IOHIDSystem | awk '/HIDIdleTime/ {print int($NF/1000000000); exit}'
}
check_idle() { # IDLE と ACTIVE を設定する(必要な時間帯だけ呼ぶ)
  IDLE=$(idle_secs); IDLE=${IDLE:-99999}
  ACTIVE=0; [ "$IDLE" -lt "$ACTIVE_IDLE" ] && ACTIVE=1
}

# ---- 通知 (ログイン中ユーザーとして表示) ----
has_user() { [ -n "$CU" ] && [ "$CU" != root ] && [ "$CU" != loginwindow ]; }
notify() { # notify 見出し 本文
  has_user || return 1
  if [ "$DRY_RUN" = 1 ]; then log "NOTIFY: $1 / $2"; return 0; fi
  launchctl asuser "$CUID" sudo -u "$CU" \
    osascript -e "display notification \"$2\" with title \"SleepGuard\" subtitle \"$1\""
}

bath_dialog() { # 「入浴済み」を押すと $1 を作る。4分で自動的に閉じる
  has_user || return 1
  if [ "$DRY_RUN" = 1 ]; then log "DIALOG: お風呂の時間です"; return 0; fi
  (
    r=$(launchctl asuser "$CUID" sudo -u "$CU" osascript \
      -e 'display dialog "22時です。区切りをつけてお風呂に入りましょう。" buttons {"あとで", "入浴済み"} default button "入浴済み" with title "SleepGuard" giving up after 240' \
      -e 'button returned of result' 2>/dev/null)
    [ "$r" = "入浴済み" ] && touch "$1" && log "bath done (button)"
  ) &
}

once() { # その夜に一度だけ true
  local f="$STATE_DIR/once-$1"
  [ -e "$f" ] && return 1
  touch "$f"
}

lock_screen() {
  log "lock"
  [ "$DRY_RUN" = 1 ] && return
  pmset displaysleepnow      # 画面を消す → パスワードが要求される(システムはスリープしない)
}

# ---- 本体 ----
once "cleanup-$DAY" && find "$STATE_DIR" -type f -mtime +3 -delete 2>/dev/null

if (( HM >= BATH_START && HM <= BATH_END )); then
  check_idle
  bath_done="$STATE_DIR/bath-$DAY"
  last_file="$STATE_DIR/last-bath-$DAY"
  if [ ! -e "$bath_done" ]; then
    if [ -e "$last_file" ] && [ "$IDLE" -ge "$BATH_AWAY" ]; then
      touch "$bath_done"; log "bath: away from Mac, assume done"
    elif [ "$ACTIVE" = 1 ]; then
      last=$(cat "$last_file" 2>/dev/null || echo 0)
      if (( NOW - last >= BATH_INTERVAL )); then
        echo "$NOW" > "$last_file"
        bath_dialog "$bath_done"
      fi
    fi
  fi

elif (( HM >= MIDNIGHT_START && HM <= MIDNIGHT_END )); then
  once "midnight-$DAY" && notify "0時です" "寝る準備をしましょう。"

elif (( HM >= WARN_START && HM <= WARN_END )); then
  once "warn-$DAY" && notify "まもなく1時です" "1時以降も使っていると、10分後に画面をロックします。"

elif (( HM >= LATE_START && HM <= LATE_END )); then
  check_idle
  if [ "$ACTIVE" = 1 ]; then
    late_file="$STATE_DIR/late-$DAY"
    if [ ! -e "$late_file" ]; then
      echo "$NOW" > "$late_file"
      notify "1時を過ぎました" "10分後もまだ使っていたら画面をロックします。保存してください。"
    else
      since=$(cat "$late_file" 2>/dev/null || echo "$NOW")
      if (( NOW - since >= LOCK_GRACE )); then
        notify "画面をロックします" "もう寝ましょう。"
        lock_screen
      fi
    fi
  fi
fi

exit 0
