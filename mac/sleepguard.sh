#!/bin/bash
# SleepGuard for macOS (全画面の文面表示・音なし・ネット遮断なし・画面ロックなし)
#   時間帯は、ユーザーの ~/.config/sleepguard/messages.md にある「## H:MM」見出しの時刻で決まる。
#   見出しの時刻から次の見出しの時刻まで(最後の見出しは LATE_END まで)が1つの時間帯。最初の見出しより前は何もしない。
#   0:00 より前に始まる時間帯は「入浴」、0:00 以降に始まる時間帯は「就寝」。
#   入浴: 使っている間、BATH_INTERVAL おきに文面を全画面に出す(sleepguard-show)。この画面は「入浴済み」を押すまで残る。
#         半透明になってから5分かけて背景が濃くなり、真っ暗に戻る(作業は続けられなくなる)。
#         「入浴済み」を押すか、BATH_AWAY 以上離席したら(表示中でも表示後でも)入浴済みとみなし、その夜はもう出さない
#   就寝: 使っている間、SLEEP_INTERVAL おき(LATE_START 以降は LATE_INTERVAL おき)に文面を全画面に出す
#   使っていなければ何もしない。
# 時刻はMacのタイムゾーン設定(現地時間)で判定する。
# launchd(root)から毎分実行される。時間帯の外ではすぐ終わる。表示中は launchd が次を起動しない。
# 文面の中身を読むのはユーザー権限で動く sleepguard-show だけ。このスクリプト(root)は見出しの時刻しか読まない。

set -u
PATH=/usr/bin:/bin:/usr/sbin:/sbin

# ===== 設定 (時刻は HHMM を数値で。先頭の0は付けない) =====
BATH_INTERVAL=600          # 入浴の時間帯に文面を出す間隔(秒)
BATH_AWAY=900              # これだけ離席したら入浴済みとみなす(秒)
SLEEP_INTERVAL=900         # 就寝の時間帯に文面を出す間隔(秒)
LATE_START=100             # これ以降は LATE_INTERVAL おきにする(HHMM)
LATE_INTERVAL=600
LATE_END=559               # 最後の時間帯の終わり(HHMM)
ACTIVE_IDLE=120            # 無操作がこの秒数未満なら「使用中」とみなす
MESSAGES=.config/sleepguard/messages.md   # 文面ファイル(ユーザーのホームからの相対パス)
# ============================================================
APP="/Library/Application Support/SleepGuard/sleepguard-show"
HEADING_RE='^## *[0-9]{1,2}:[0-9]{2}([^0-9]|$)'

# root(launchd から起動)のときは SG_* をすべて無視する。
# 一般ユーザーのときは、テスト用に SG_DRY_RUN=1 と SG_STATE_DIR を指定した場合だけ動く。
#   SG_MESSAGES: 文面ファイル(見出しの時刻だけ読む)  SG_SHOW_RC: 表示アプリの終了コードのつもりで返す値
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
fmt() { date -r "${2:-$NOW}" "+$1" 2>/dev/null || date -d "@${2:-$NOW}" "+$1"; } # fmt 書式 [epoch]
read -r HM DAY < <(fmt '%H%M %Y%m%d')
HM=$((10#$HM))
NIGHT=$(fmt %Y%m%d $((NOW - 43200)))   # 夜の日付(正午より前は前日の夜とみなす)。state ファイルの名前に使う

# ---- ユーザー ----
has_user() { [ -n "$CU" ] && [ "$CU" != root ] && [ "$CU" != loginwindow ]; }
has_user || exit 0   # ログイン画面などでは何もしない
CUID=$(id -u "$CU" 2>/dev/null || echo "")
user_home() { dscl . -read "/Users/$CU" NFSHomeDirectory 2>/dev/null | awk '{print $2; exit}'; }

# ---- 時間帯 ----
# 夜の順序: 12:00 を起点にして、0:00〜11:59 は翌日として 2400 を足す
nkey() { if (( $1 >= 1200 )); then echo "$1"; else echo $(( $1 + 2400 )); fi; }

headings() { # 文面ファイルの「## H:MM」見出しの時刻を H:MM で出す。それ以外の行は読まない
  local out=""
  if [ "$DRY_RUN" = 1 ]; then
    [ -n "${SG_MESSAGES:-}" ] && out=$(grep -E "$HEADING_RE" "$SG_MESSAGES" 2>/dev/null)
  else
    # root では読まず、ファイルの持ち主の権限で読む
    out=$(sudo -u "$CU" grep -E "$HEADING_RE" "$(user_home)/$MESSAGES" 2>/dev/null)
  fi
  printf '%s\n' "$out" | sed -nE 's/^## *([0-9]{1,2}:[0-9]{2}).*/\1/p' | head -20
}

# いまの時間帯: BAND(見出しの H:MM)と BAND_KIND(bath か sleep)
BAND=""; BAND_KIND=""
NOWKEY=$(nkey "$HM")
if (( NOWKEY <= $(nkey "$LATE_END") )); then
  while read -r key label; do
    [ -n "$key" ] || continue
    (( key <= NOWKEY )) || break
    BAND=$label
    if (( key < 2400 )); then BAND_KIND=bath; else BAND_KIND=sleep; fi
  done < <(
    for h in $(headings); do
      hh=${h%%:*}; mm=${h##*:}
      (( 10#$hh < 24 && 10#$mm < 60 )) || continue
      echo "$(nkey $((10#$hh * 100 + 10#$mm))) $h"
    done | sort -n -k1,1
  )
fi
[ -n "$BAND" ] || exit 0

mkdir -p "$STATE_DIR"

# ---- 使用状況 ----
idle_secs() {
  if [ -n "$IDLE_OVERRIDE" ]; then echo "$IDLE_OVERRIDE"; return; fi
  ioreg -r -d 1 -k HIDIdleTime -c IOHIDSystem | awk '/HIDIdleTime/ {print int($NF/1000000000); exit}'
}
check_idle() { # IDLE と ACTIVE を設定する
  IDLE=$(idle_secs); IDLE=${IDLE:-99999}
  ACTIVE=0; [ "$IDLE" -lt "$ACTIVE_IDLE" ] && ACTIVE=1
}

# ---- 表示 (ログイン中ユーザーとして) ----
show() { # show 種別 時刻: 文面を全画面に出し、閉じられるまで待つ。「入浴済み」なら 2、離席で閉じたら 3 を返す
  log "SHOW: $1 $2"
  if [ "$DRY_RUN" = 1 ]; then return "${SG_SHOW_RC:-0}"; fi
  # sudo -H: macOS の sudo は HOME を引き継ぐので、ユーザーのホームに直す
  launchctl asuser "$CUID" sudo -H -u "$CU" "$APP" --kind "$1" --band "$2" --day "$NIGHT" --away "$BATH_AWAY"
}

once() { # その日に一度だけ true
  local f="$STATE_DIR/once-$1"
  [ -e "$f" ] && return 1
  touch "$f"
}

# ---- 本体 ----
# 消すのはこのスクリプトが作るファイルだけ(サブディレクトリには降りない)
once "cleanup-$DAY" && find "$STATE_DIR" -maxdepth 1 -type f \
  \( -name 'once-*' -o -name 'bath-*' -o -name 'last-*' -o -name 'late-*' \) \
  -mtime +3 -delete 2>/dev/null

check_idle
if [ "$BAND_KIND" = bath ]; then
  bath_done="$STATE_DIR/bath-$NIGHT"
  last_file="$STATE_DIR/last-bath-$NIGHT"
  if [ ! -e "$bath_done" ]; then
    if [ -e "$last_file" ] && [ "$IDLE" -ge "$BATH_AWAY" ]; then
      touch "$bath_done"; log "bath: away from Mac, assume done"
    elif [ "$ACTIVE" = 1 ]; then
      last=$(cat "$last_file" 2>/dev/null || echo 0)
      if (( NOW - last >= BATH_INTERVAL )); then
        echo "$NOW" > "$last_file"
        show bath "$BAND"; rc=$?
        if [ "$rc" = 2 ]; then touch "$bath_done"; log "bath done (button)"
        elif [ "$rc" = 3 ]; then touch "$bath_done"; log "bath: away from Mac, assume done"
        elif [ "$rc" != 0 ]; then log "show failed ($rc)"; fi
      fi
    fi
  fi
else
  if [ "$ACTIVE" = 1 ]; then
    last_file="$STATE_DIR/last-sleep-$NIGHT"
    interval=$SLEEP_INTERVAL
    (( HM >= LATE_START && HM <= LATE_END )) && interval=$LATE_INTERVAL
    last=$(cat "$last_file" 2>/dev/null || echo 0)
    if (( NOW - last >= interval )); then
      echo "$NOW" > "$last_file"
      show sleep "$BAND"; rc=$?
      [ "$rc" = 0 ] || log "show failed ($rc)"
    fi
  fi
fi

exit 0
