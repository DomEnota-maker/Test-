#!/usr/bin/env python3
"""Acceptance smoke for the VL80S reference card in beginner, expert and study views."""
import base64
import json
import re
import time
from pathlib import Path

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


FRAMEWORK_ASSET = Path(__file__).resolve().parents[1] / "app/src/main/assets/technical/diagnostic_framework_v2.json"
FRAMEWORK_RAW = json.loads(FRAMEWORK_ASSET.read_text(encoding="utf-8"))
PANTOGRAPH = next(item for item in FRAMEWORK_RAW["modules"] if item["scenarioId"] == "pantograph-no-rise")
PANTOGRAPH_NODES = {item["id"]: item for item in PANTOGRAPH["nodes"]}
RESPONSE_LABEL = {"YES": "Да", "NO": "Нет", "UNKNOWN": "Не знаю"}


def locate_anywhere(text, down=30, up=36):
    try:
        return scroll_until(text, down)
    except AssertionError:
        return scroll_up_until(text, up)


def reset_reference_to_start():
    first_step = "Шаг 1; дальнейший вопрос зависит от ответа"
    # During exhaustive edge coverage we deliberately stay inside the same
    # diagnostic card. Reopening the drawer for every edge made the test
    # vulnerable to matching the non-clickable screen title "Диагностика".
    try:
        locate_anywhere("Начать заново", 18, 28)
        tap("Начать заново", 0)
        return locate_anywhere(first_step, 16, 24)
    except AssertionError:
        pass
    try:
        return locate_anywhere(first_step, 12, 20)
    except AssertionError:
        open_reference()
        try:
            locate_anywhere("Начать заново", 18, 28)
            tap("Начать заново", 0)
        except AssertionError:
            pass
        return locate_anywhere(first_step, 16, 24)


def shortest_prefixes():
    start = PANTOGRAPH["startNodeId"]
    prefixes = {start: []}
    queue = [start]
    while queue:
        node_id = queue.pop(0)
        node = PANTOGRAPH_NODES[node_id]
        for response in ("YES", "NO", "UNKNOWN"):
            nxt = node["next"][response]
            if nxt != "__end__" and nxt not in prefixes:
                prefixes[nxt] = prefixes[node_id] + [(node_id, response)]
                queue.append(nxt)
    if set(prefixes) != set(PANTOGRAPH_NODES):
        raise AssertionError("Not every diagnostic node has a reachable UI prefix")
    return prefixes


def prepare_node(node_id, prefix):
    reset_reference_to_start()
    for source_id, response in prefix:
        locate_anywhere(PANTOGRAPH_NODES[source_id]["question"], 14, 18)
        tap(RESPONSE_LABEL[response], 2)
        nxt = PANTOGRAPH_NODES[source_id]["next"][response]
        if nxt != "__end__":
            locate_anywhere(PANTOGRAPH_NODES[nxt]["question"], 18, 24)
    locate_anywhere(PANTOGRAPH_NODES[node_id]["question"], 18, 24)


def exercise_every_graph_edge_on_device():
    prefixes = shortest_prefixes()
    evidence = ["node\tresponse\tnext\tmeaning_verified"]
    for node_id in PANTOGRAPH_NODES:
        prepare_node(node_id, prefixes[node_id])
        node = PANTOGRAPH_NODES[node_id]
        for index, response in enumerate(("YES", "NO", "UNKNOWN")):
            if index:
                locate_anywhere("Назад на шаг", 32, 36)
                tap("Назад на шаг", 0)
                locate_anywhere(node["question"], 18, 24)
            tap(RESPONSE_LABEL[response], 2)
            locate_anywhere(node["answers"][response], 26, 30)
            nxt = node["next"][response]
            if nxt == "__end__":
                locate_anywhere("Вопросы пройдены. Выводы включены в шаблон доклада.", 24, 30)
            else:
                locate_anywhere(PANTOGRAPH_NODES[nxt]["question"], 24, 30)
            evidence.append(f"{node_id}\t{response}\t{nxt}\tPASS")
    (OUT / "framework-v2-edge-coverage.tsv").write_text("\n".join(evidence) + "\n", encoding="utf-8")
    if len(evidence) != 1 + len(PANTOGRAPH_NODES) * 3:
        raise AssertionError("Incomplete on-device response-edge coverage")


def write_appearance_preferences(theme):
    xml = (
        '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n'
        "<map>\n"
        '    <string name="accent_palette">BLUE</string>\n'
        f'    <string name="theme_mode">{theme}</string>\n'
        "</map>\n"
    )
    encoded = base64.b64encode(xml.encode("utf-8")).decode("ascii")
    command = (
        f"run-as {PACKAGE} sh -c 'mkdir -p shared_prefs && "
        f"echo {encoded} | base64 -d > shared_prefs/calculation_inputs.xml'"
    )
    adb("shell", command)


def restart_visual_environment(font_scale, theme):
    adb("shell", "settings", "put", "system", "font_scale", font_scale)
    adb("shell", "am", "force-stop", PACKAGE)
    write_appearance_preferences(theme)
    adb("shell", "monkey", "-p", PACKAGE, "1")
    wait("Железнодорожный помощник")


def long_card_visual_stress():
    restart_visual_environment("1.3", "DARK")
    open_reference()
    locate_anywhere("Изучение", 10, 18)
    tap("Изучение", 0)
    locate_anywhere("Изучить элемент", 24, 30)
    shot("framework-v2-long-dark-font130-top")
    locate_anywhere("Л-13У1 и Л-14М1 в исторических материалах", 58, 62)
    shot("framework-v2-long-dark-font130-middle")
    locate_anywhere("Принудительное включение/обход разрешающей цепи — отдельный unsafe-класс", 72, 76)
    locate_anywhere("Источник:", 14, 18)
    shot("framework-v2-long-dark-font130-bottom")
    if restricted_visible(18) != {"field", "unsafe"}:
        raise AssertionError("Restricted layer lost during dark/font130 long-card stress")


try:
    open_reference()
    scroll_until("Видно ли повреждение токоприёмника или контактного провода?")
    adb("shell", "input", "swipe", "520", "1850", "520", "1050", "390")
    tap("Нет", 0)
    scroll_up_until("Шаг 2;", 18)
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
    scroll_until("Показать подробные проверки и материалы", 40)
    tap("Показать подробные проверки и материалы", 0)
    scroll_until("Проверки по уровню допуска", 30)

    # Screenshot and inspect each independent knowledge-mode / depth combination.
    set_emergency_off()
    for index, (mode, depth, marker) in enumerate((
        ("Базовый", "Минимальная", "Материалы по проверке"),
        ("Базовый", "Стандартная", "Материалы по проверке"),
        ("Базовый", "Подробная", "Материалы по проверке"),
        ("Расширенные знания", "Минимальная", "Карта направлений поиска"),
        ("Расширенные знания", "Стандартная", "Карта направлений поиска"),
        ("Расширенные знания", "Подробная", "Карта направлений поиска"),
    )):
        if index:
            open_menu("Настройки")
        scroll_up_until("Базовый", 18)
        tap(mode, 12)
        tap(depth, 3)
        open_reference()
        scroll_until(marker, 16)
        shot(f"framework-v2-{mode.replace(' ', '-')}-{depth}")

    scroll_up_until("Изучение")
    tap("Изучение", 0)
    scroll_until("Изучить элемент", 20)
    scroll_until("1. Что это", 3)
    for marker in (
        "2. Назначение",
        "3. Устройство",
        "4. Работа в системе",
        "5. Диагностическое значение",
        "6. Эксплуатационный слой",
    ):
        # The card grows after each level, so verify every transition instead
        # of firing five blind taps at a moving control.
        for attempt in range(2):
            try:
                locate_anywhere(marker, 8, 12)
                break
            except AssertionError:
                tap("Следующий уровень", 24)
        else:
            raise AssertionError(f"Object knowledge level did not advance to: {marker}")
    shot("framework-v2-six-object-levels")
    if restricted_visible():
        raise AssertionError("Restricted material visible with separate gate off")

    check_warning(require_warning=False)
    open_reference()
    scroll_until("Изучение", 6)
    tap("Изучение", 0)
    if restricted_visible() != {"field", "unsafe"}:
        raise AssertionError("Restricted entries not separately available behind enabled gate")
    shot("framework-v2-restricted-separate-layer")

    exercise_every_graph_edge_on_device()
    long_card_visual_stress()
finally:
    adb("shell", "settings", "put", "system", "font_scale", "1.0")
    (OUT / "framework-v2-crash-logcat.txt").write_bytes(adb("logcat", "-d", "-b", "crash"))

assert b"Process: ru.railbrake.calculator" not in (OUT / "framework-v2-crash-logcat.txt").read_bytes()
print("Diagnostic Framework v2 GOLDEN REFERENCE PASS: six mode-depth views, all 24 graph response edges, progressive object, independent gate and long-card visual stress")
