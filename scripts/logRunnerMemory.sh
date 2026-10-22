#!/bin/sh
# Запускает команду и каждые MEMORY_LOG_INTERVAL секунд печатает память и swap
# раннера, свободное место на диске и RSS процессов QEMU. Раннер, которому не хватило
# памяти, обрывается с "The runner has received a shutdown signal" без отчётов
# тестов, а эти строки остаются в логе job до момента обрыва.
# 🤖 Generated with Claude Opus 5.5
set -u
interval="${MEMORY_LOG_INTERVAL:-30}"

print_sample() {
  memory=$(free -m | awk '/^Mem:/ { printf "used=%sM available=%sM", $3, $7 } /^Swap:/ { printf " swap=%sM", $3 }')
  qemu=$(ps -eo rss=,args= | awk '/qemu-system/ && !/awk/ { total += $1 } END { printf "%dM", total / 1024 }')
  disk=$(df -Pm . | awk 'NR == 2 { printf "%sM", $4 }')
  echo "runner-memory $(date -u +%H:%M:%S) $memory qemu_rss=$qemu disk_free=$disk"
}

(
  while :; do
    # Без перенаправления осиротевший sleep держит pipe лога после остановки
    # сэмплера, и вызывающий процесс ждёт его до конца интервала.
    # 🤖 Generated with Claude Opus 5.5
    sleep "$interval" >/dev/null 2>&1
    print_sample
  done
) &
sampler=$!
"$@"
status=$?
kill "$sampler" 2>/dev/null
exit "$status"
