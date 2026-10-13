#!/usr/bin/env bash
# Проверка APK на эмуляторе (x86_64): установка, selftest.py внутри приложения, вывод — из logcat.
set -uo pipefail
APK="$1"
adb wait-for-device
adb install -r -g "$APK" || exit 1
adb logcat -c
adb shell am start -n dev.frx.userbot/.MainActivity --ez selftest true
end=$((SECONDS + 600))
result=""
while (( SECONDS < end )); do
  sleep 5
  out="$(adb logcat -d -s FRX:I)"
  if grep -q "SELFTEST OK" <<<"$out"; then result=ok; break; fi
  if grep -q "SELFTEST FAIL\|\[selftest\] Ошибка" <<<"$out"; then result=fail; break; fi
done
adb logcat -d -s FRX:I | sed 's/^.*FRX *: //' | tee selftest.log
echo "----- crash/AndroidRuntime -----"
adb logcat -d -s AndroidRuntime:E DEBUG:F linker:* | tail -60
[[ "$result" == ok ]] || { echo "selftest: ${result:-нет ответа за 10 минут}"; exit 1; }
