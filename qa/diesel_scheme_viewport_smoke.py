#!/usr/bin/env python3
"""Verify diesel diagrams stay inside their canvas and a swipe scrolls the page."""
import time

from tem2_android_smoke import PACKAGE, adb, label, open_menu, shot, tap, tree, wait


def scroll_until(text, attempts=14):
    for _ in range(attempts):
        root = tree()
        if any(text in label(node) for node in root.iter("node")):
            return
        adb("shell", "input", "swipe", "520", "1770", "520", "680", "420")
        time.sleep(.4)
    raise AssertionError(f"Page did not scroll to {text}")


def scroll_up_until(text, attempts=8):
    for _ in range(attempts):
        root = tree()
        if any(text in label(node) for node in root.iter("node")):
            return
        adb("shell", "input", "swipe", "520", "680", "520", "1720", "420")
        time.sleep(.4)
    raise AssertionError(f"Page did not scroll back to {text}")


for family, slug in (("ЧМЭ3", "chme3"), ("ТЭМ2", "tem2")):
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "monkey", "-p", PACKAGE, "1")
    wait("Железнодорожный помощник")
    open_menu("Локомотивы / атлас")
    wait("Выберите серию и тип материала")
    tap(family, 10)
    tap("Интерактивный атлас")
    wait("Интерактивная схема " + family)
    shot(f"{slug}-diagram-before-scroll")
    scroll_until("Элементы схемы")
    shot(f"{slug}-diagram-after-scroll")
    scroll_up_until("Показать целиком")
    tap("Показать целиком", 0)
    shot(f"{slug}-diagram-reset")

print("CHME3 and TEM2 diagram viewport and page scrolling PASS")
