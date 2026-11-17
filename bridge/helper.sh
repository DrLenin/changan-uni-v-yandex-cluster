#!/system/bin/sh
# Помощник моста ClusterBridge с правами оболочки ADB (docs/how-it-works.md).
# Мост пишет в лог «EN_MAP on/off», помощник переносит Яндекс между дисплеями:
#   on  — на дисплей 1 (видеовход приборки) во всю высоту 1920×720: строка прибытия Яндекса уходит
#         в нижние 80 px, которые приборка не показывает, и не налезает на её индикатор режима;
#   off — обратно на основной экран, разжимает и выводит наверх то, что было открыто до этого
#         (пока Яндекс на виду, его уведомление без подсказки); «off show» — оставляет карту на виду
#         (мост читает подсказку с экрана через службу спецвозможностей).
# Команды внутри цикла — с </dev/null: иначе они съедают поток logcat.
# Обычному приложению am display move-stack / am stack resize недоступны, поэтому отдельный процесс.
# Запускает мост через собственный adbd ГУ (AdbLocal) при каждом своём старте, в т. ч. после загрузки ГУ.
L=/data/local/tmp/helper.log
NAVI=ru.yandex.yandexnavi/.core.NavigatorActivity
log() { echo "$(date +%T) $*" >> $L; }

# Стек и дисплей, где сейчас Яндекс.
ya_stack() { dumpsys activity activities </dev/null | grep "A=ru.yandex.yandexnavi" | head -1 | sed 's/.*StackId=\([0-9]*\).*/\1/'; }
ya_display() {
  dumpsys activity activities </dev/null | while read l; do
    case "$l" in
      "Display #"*) d=${l#Display #}; d=${d%% *} ;;
      *"A=ru.yandex.yandexnavi"*) echo $d; break ;;
    esac
  done
}
# Верхнее окно основного экрана: компонент «пакет/.Activity».
top0() {
  dumpsys activity activities </dev/null | while read l; do
    case "$l" in
      "Display #0"*) on=1 ;;
      "Display #"*) [ -n "$on" ] && break ;;
      *"ActivityRecord{"*) [ -n "$on" ] && { c=${l#*ActivityRecord\{* u0 }; echo ${c%% *}; break; } ;;
    esac
  done
}

# Второй экземпляр (мост запускает помощника при каждом своём старте) — сразу выходим.
P=/data/local/tmp/helper.pid
if [ -f $P ] && grep -q helper.sh /proc/$(cat $P)/cmdline 2>/dev/null; then exit 0; fi
echo $$ > $P
log "старт"
logcat -v brief -T 1 -s ClusterBridge:I | while read line; do
  case "$line" in
    *"EN_MAP on"*)
      s=$(ya_stack)
      if [ -z "$s" ]; then am start --display 1 -n $NAVI </dev/null > /dev/null 2>&1; sleep 2; s=$(ya_stack); fi
      [ "$(ya_display)" != 1 ] && am display move-stack $s 1 </dev/null
      am stack resize $s 0 0 1920 720 </dev/null
      log "Яндекс на приборке, стек $s"
      ;;
    *"EN_MAP off"*)
      s=$(ya_stack)
      if [ -n "$s" ] && [ "$(ya_display)" = 1 ]; then
        t=$(top0)
        case "$line" in *"show"*) t="-" ;; esac  # мост читает подсказку с видимого Яндекса — не прятать его
        am display move-stack $s 0 </dev/null
        am stack resize $s 0 0 1920 720 </dev/null
        # Не вышло поднять прежнее окно (не экспортировано и т. п.) — домашний экран, чтобы Яндекс ушёл с глаз.
        if [ "$t" = "-" ]; then t="ничего, карта на виду"
        elif [ -z "$t" ] || am start -n $t </dev/null 2>&1 | grep -qiE "error|exception"; then
          am start -a android.intent.action.MAIN -c android.intent.category.HOME </dev/null > /dev/null 2>&1
          t="домашний экран"
        fi
        log "Яндекс на основном экране, наверх вернул $t"
      fi
      ;;
  esac
done
