#!/usr/bin/env bash
# step.sh ИМЯ команда… — запускает шаг сборки, пишет вывод в out/logs/ИМЯ.log и выводит конец лога
# аннотацией GitHub (error при ошибке, notice при успехе): аннотации видны через API без скачивания логов.
name="$1"; shift
mkdir -p out/logs
log="out/logs/$name.log"
"$@" 2>&1 | tee "$log"
rc=${PIPESTATUS[0]}
annotate() {  # уровень строк
  tail -n "$2" "$log" | python3 -c "
import sys
d = sys.stdin.read().replace('\r', '')[-60000:]
d = d.replace('%', '%25').replace('\n', '%0A')
print('::$1 title=$name::' + d)"
}
if [ "$rc" -ne 0 ]; then annotate error 120; else annotate notice 15; fi
exit "$rc"
