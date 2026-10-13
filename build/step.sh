#!/usr/bin/env bash
# step.sh ИМЯ команда… — запускает шаг сборки, пишет вывод в out/logs/ИМЯ.log и выводит конец лога
# аннотациями GitHub (error при ошибке, notice при успехе): аннотации видны через API без скачивания логов.
# Длинный конец лога режется на несколько аннотаций (у одной аннотации ограничена длина).
name="$1"; shift
mkdir -p out/logs
log="out/logs/$name.log"
"$@" 2>&1 | tee "$log"
rc=${PIPESTATUS[0]}
annotate() {  # уровень сколько_строк сколько_кусков
  tail -n "$2" "$log" | python3 -c "
import re, sys
d = re.sub(r'\x1b\[[0-9;]*m', '', sys.stdin.read().replace('\r', ''))
chunks, size = [], 3800
while d and len(chunks) < $3:
    chunks.insert(0, d[-size:]); d = d[:-size]
for i, c in enumerate(chunks, 1):
    c = c.replace('%', '%25').replace('\n', '%0A')
    print(f'::$1 title=$name ({i}/{len(chunks)})::' + c)"
}
if [ "$rc" -ne 0 ]; then annotate error 400 4; else annotate notice 15 1; fi
exit "$rc"
