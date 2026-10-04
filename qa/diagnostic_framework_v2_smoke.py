#!/usr/bin/env python3
"""Acceptance smoke for the VL80S reference card in beginner, expert and study views."""
import re
import time

from tem2_android_smoke import PACKAGE, OUT, adb, label, open_menu, shot, tap, tree, wait


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
finally:
    (OUT / "framework-v2-crash-logcat.txt").write_bytes(adb("logcat", "-d", "-b", "crash"))

assert b"Process: ru.railbrake.calculator" not in (OUT / "framework-v2-crash-logcat.txt").read_bytes()
print("Diagnostic Framework v2 beginner, expert, study and minimal-depth UI PASS")
