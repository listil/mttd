#!/usr/bin/env bash
# 실기기 게임 로그에서 "지금부터 다음 행동까지" 구간만 잘라내는 캡처 도구.
#
# SessionAggregator 의 파싱 로직을 고칠 때마다 매번 손으로
# (adb shell stat 으로 크기 확인 -> 게임에서 행동 -> tail -c 로 델타 추출)
# 하던 걸 두 명령으로 정형화한다.
#
# 사용법:
#   1. scripts/capture-log.sh mark              # 지금 시점을 기준점으로 저장
#   2. (게임에서 재현하려는 행동을 한다)
#   3. scripts/capture-log.sh pull <이름>        # 기준점 이후 델타를 파일로 저장
#
# 저장 위치: app/src/test/resources/fixtures/<이름>.log
# (테스트에서 loadFixture("<이름>") 로 그대로 읽어 SessionAggregatorTest 에 재현 가능)
#
# 기기가 여러 대 연결돼 있으면 ADB_SERIAL 환경변수로 지정:
#   ADB_SERIAL="adb-XXXX._adb-tls-connect._tcp" scripts/capture-log.sh mark

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MARK_FILE="$REPO_ROOT/.log-capture-mark"
FIXTURE_DIR="$REPO_ROOT/app/src/test/resources/fixtures"

# UserService.kt 의 knownGamePackages 와 동일한 후보 목록.
GAME_PACKAGES=(com.xd.TLglobal com.xindong.torchlight com.xd.TLglobalTap)
LOG_SUBPATH="files/UE4Game/UE_game/UE_game/Saved/Logs/UE_game.log"

adb_cmd() {
    if [[ -n "${ADB_SERIAL:-}" ]]; then
        adb -s "$ADB_SERIAL" "$@"
    else
        adb "$@"
    fi
}

resolve_log_path() {
    for pkg in "${GAME_PACKAGES[@]}"; do
        local path="/sdcard/Android/data/$pkg/files/$( : )"
        path="/sdcard/Android/data/$pkg/$LOG_SUBPATH"
        if adb_cmd shell "[ -f '$path' ] && echo yes" | grep -q yes; then
            echo "$path"
            return 0
        fi
    done
    echo "게임 로그 파일을 못 찾았습니다 (설치된 게임 패키지 확인 필요)" >&2
    return 1
}

cmd_mark() {
    local log_path
    log_path="$(resolve_log_path)"
    local size
    size="$(adb_cmd shell "stat -c '%s' '$log_path'" | tr -d '\r')"
    echo "$log_path" > "$MARK_FILE"
    echo "$size" >> "$MARK_FILE"
    echo "기준점 저장: $size bytes ($log_path)"
    echo "이제 게임에서 재현하려는 행동을 하고, 완료되면:"
    echo "  scripts/capture-log.sh pull <이름>"
}

cmd_pull() {
    local name="${1:?사용법: capture-log.sh pull <이름>}"
    if [[ ! -f "$MARK_FILE" ]]; then
        echo "기준점이 없습니다. 먼저 'scripts/capture-log.sh mark' 를 실행하세요." >&2
        exit 1
    fi
    local log_path start_size
    log_path="$(sed -n '1p' "$MARK_FILE")"
    start_size="$(sed -n '2p' "$MARK_FILE")"

    local end_size
    end_size="$(adb_cmd shell "stat -c '%s' '$log_path'" | tr -d '\r')"

    if [[ "$end_size" -lt "$start_size" ]]; then
        echo "경고: 로그 파일이 기준점보다 작아졌습니다 (게임이 재시작되며 로그가 새로 시작된 것 같습니다)." >&2
        echo "기준점(mark)을 다시 잡고 재시도하세요." >&2
        exit 1
    fi

    mkdir -p "$FIXTURE_DIR"
    local out_file="$FIXTURE_DIR/$name.log"
    adb_cmd exec-out "tail -c +$((start_size + 1)) '$log_path'" > "$out_file"

    local lines
    lines="$(wc -l < "$out_file" | tr -d ' ')"
    echo "저장됨: $out_file ($lines 줄, $((end_size - start_size)) bytes)"
    echo "테스트에서: loadFixture(\"$name\") 로 로드해서 SessionAggregatorTest 에 assertion 추가"
}

case "${1:-}" in
    mark) cmd_mark ;;
    pull) cmd_pull "${2:-}" ;;
    *)
        echo "사용법: $0 mark | pull <이름>" >&2
        exit 1
        ;;
esac
