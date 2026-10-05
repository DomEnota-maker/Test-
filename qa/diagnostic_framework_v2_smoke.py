#!/usr/bin/env python3
"""Acceptance smoke for the VL80S reference card in beginner, expert and study views."""
import re
import time

from tem2_android_smoke import (PACKAGE, OUT, adb, bounds, check_warning,
                               find, label, open_menu, shot, tap, tap_node, tree, wait)


def scroll_until(text, swipes=12):
    for attempt in range(swipes + 1):
        root = tree()
        if any(text in label(n) for n in root.iter("node")):
            return root
        if attempt < swipes:
            adb("shell", "input", "swipe", "520", "1840", "520", "500", "390")
            time.sleep(.5)
    raise AssertionError(f"Missing after scrolling: {text}")


def scroll_up_until(text, swipes=18):
    for attempt in range(swipes + 1):
        root = tree()
        if any(text in label(n) for n in root.iter("node")):
            return root
        if attempt < swipes:
            adb("shell", "input", "swipe", "520", "900", "520", "2100", "390")
            time.sleep(.5)
    raise AssertionError(f"Missing after scrolling upward: {text}")


def open_reference():
    open_menu("Диагностика")
    tap("ВЛ80С", 0)
    wait("Диагностика ВЛ80С")
    tap("Токоприёмник не поднимается", 6)
    root = wait("Локомотив: ВЛ80С")
    labels = [label(n) for n in root.iter("node")]
    for prefix in ("Исполнение:", "Система:", "Реакция:", "Что произошло:"):
        if not any(x.startswith(prefix) for x in labels):
            raise AssertionError(f"Missing constant card core: {prefix}")
    if any(re.search(r"\b(?:VL-EQ|VL-SYS|pnr-)[A-Za-z0-9-]*", x) for x in labels):
        raise AssertionError("Internal knowledge identifier visible in card")


def set_emergency_off():
    open_menu("Настройки")
    for _ in range(14):
        root = tree()
        title = find(root, "Расширенный режим включён")
        if title is not None:
            y = (bounds(title)[1] + bounds(title)[3]) // 2
            switches = [n for n in root.iter("node") if n.get("checkable") == "true"
                        and abs((bounds(n)[1] + bounds(n)[3]) // 2 - y) < 150]
            if not switches:
                raise AssertionError("Emergency mode switch unavailable")
            tap_node(switches[0])
            wait("Расширенный режим выключен")
            return
        if find(root, "Расширенный режим выключен") is not None:
            return
        adb("shell", "input", "swipe", "500", "1800", "500", "500", "420")
        time.sleep(.5)
    raise AssertionError("Emergency mode setting unavailable")


def restricted_visible(swipes=35):
    found = set()
    previous = None
    stationary = 0
    for i in range(swipes + 1):
        labels = tuple(label(n) for n in tree().iter("node") if label(n))
        if any("Полевая практика · отдельное ограничение" in x for x in labels):
            found.add("field")
        if any("Нештатный метод · не применять как рекомендацию" in x for x in labels):
            found.add("unsafe")
        if found == {"field", "unsafe"}:
            break
        stationary = stationary + 1 if labels == previous else 0
        if stationary >= 2:
            break
        previous = labels
        if i < swipes:
            adb("shell", "input", "swipe", "520", "1850", "520", "470", "360")
            time.sleep(.25)
    return found


try:
    open_reference()
    scroll_until("Видно ли повреждение токоприёмника или контактного провода?")
    adb("shell", "input", "swipe", "520", "1850", "520", "1050", "390")
    tap("Нет", 0)
    wait("Шаг 2;")
    scroll_up_until("Направление проверки")
    shot("framework-v2-beginner")

    open_menu("Настройки")
    tap("Расширенные знания", 12)
    tap("Подробная", 2)
    open_reference()
    scroll_until("Карта направлений поиска")
    shot("framework-v2-expert")
    for _ in range(3):
        adb("shell", "input", "swipe", "520", "500", "520", "1800", "320")
        time.sleep(.3)
    tap("Изучение", 0)
    scroll_until("Изучение системы", 18)
    scroll_until("Источник:", 24)
    shot("framework-v2-study")

    open_menu("Настройки")
    tap("Минимальная", 12)
    open_reference()
    scroll_until("Карта направлений поиска")
    shot("framework-v2-expert-minimal")
    scroll_until("Показать подробные проверки и материалы")
    tap("Показать подробные проверки и материалы", 0)
    scroll_until("Проверки по уровню допуска", 30)

    # Screenshot and inspect each independent knowledge-mode / depth combination.
    set_emergency_off()
    for mode, depth, marker in (
        ("Базовый", "Минимальная", "Материалы по проверке"),
        ("Базовый", "Стандартная", "Материалы по проверке"),
        ("Базовый", "Подробная", "Материалы по проверке"),
        ("Расширенные знания", "Минимальная", "Карта направлений поиска"),
        ("Расширенные знания", "Стандартная", "Карта направлений поиска"),
        ("Расширенные знания", "Подробная", "Карта направлений поиска"),
    ):
        open_menu("Настройки")
        tap(mode, 12)
        tap(depth, 3)
        open_reference()
        scroll_until(marker, 16)
        shot(f"framework-v2-{mode.replace(' ', '-')}-{depth}")

    scroll_up_until("Изучение")
    tap("Изучение", 0)
    scroll_until("Изучить элемент", 20)
    scroll_until("1. Что это", 3)
    for _ in range(5):
        tap("Следующий уровень", 18)
    scroll_until("6. Эксплуатационный слой", 18)
    shot("framework-v2-six-object-levels")
    if restricted_visible():
        raise AssertionError("Restricted material visible with separate gate off")

    check_warning()
    open_reference()
    scroll_until("Изучение", 6)
    tap("Изучение", 0)
    if restricted_visible() != {"field", "unsafe"}:
        raise AssertionError("Restricted entries not separately available behind enabled gate")
    shot("framework-v2-restricted-separate-layer")
finally:
    (OUT / "framework-v2-crash-logcat.txt").write_bytes(adb("logcat", "-d", "-b", "crash"))

assert b"Process: ru.railbrake.calculator" not in (OUT / "framework-v2-crash-logcat.txt").read_bytes()
print("Diagnostic Framework v2 six mode-depth views, progressive object and independent gate PASS")
