#!/usr/bin/env python3
"""ADB smoke for both TEM2 working profiles on an API 34 emulator."""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "ru.railbrake.calculator"
OUT = Path(os.environ.get("QA_OUT", "qa-evidence"))
OUT.mkdir(parents=True, exist_ok=True)
counter = 0

def adb(*args):
    return subprocess.check_output(["adb", *args], timeout=45)

def tree():
    global counter
    for attempt in range(8):
        subprocess.run(["adb", "shell", "uiautomator", "dump", "--compressed", "/sdcard/tem2-ui.xml"],
                       check=False, stdout=subprocess.DEVNULL, timeout=45)
        data = adb("exec-out", "cat", "/sdcard/tem2-ui.xml")
        end = data.find(b"</hierarchy>")
        if end >= 0:
            counter += 1
            data = data[:end + len(b"</hierarchy>")]
            (OUT / f"ui-{counter:03d}.xml").write_bytes(data)
            return ET.fromstring(data)
        time.sleep(1)
    raise AssertionError("uiautomator hierarchy unavailable")

def label(node):
    return node.get("text") or node.get("content-desc") or ""

def bounds(node):
    a = [int(x) for x in re.findall(r"\d+", node.get("bounds", ""))]
    if len(a) != 4:
        raise AssertionError(f"Missing bounds for {label(node)}")
    return a

def tap_node(node):
    x1, y1, x2, y2 = bounds(node)
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(.7)

def find(root, text):
    return next((n for n in root.iter("node") if label(n) == text), None)

def button(root, text):
    node=find(root,text)
    if node is None: return None
    parents={c:p for p in root.iter() for c in p}
    while node.get("clickable")!="true" and node in parents:
        node=parents[node]
    return node if node.get("clickable")=="true" else None

def wait(text, timeout=60):
    deadline = time.monotonic()+timeout
    while time.monotonic() < deadline:
        root=tree()
        if any(text in label(n) for n in root.iter("node")):
            return root
        time.sleep(1)
    raise AssertionError(f"Missing UI text: {text}")

def tap(text, vertical_swipes=2):
    for attempt in range(vertical_swipes+1):
        root=tree()
        node=find(root,text)
        if node is not None:
            parents={c:p for p in root.iter() for c in p}
            while (
                node.get("clickable") != "true"
                and node.get("checkable") != "true"
                and node in parents
            ):
                node=parents[node]
            if node.get("clickable") != "true" and node.get("checkable") != "true":
                raise AssertionError(f"No actionable target for {text}")
            tap_node(node)
            return
        if attempt < vertical_swipes:
            adb("shell","input","swipe","500","1700","500","520","420")
            time.sleep(.7)
    raise AssertionError(f"Missing tap target: {text}")

def open_menu(text):
    tap("☰", 0)
    wait(text)
    tap(text)

def working(profile):
    open_menu("Главная") if not any("Сегодня работаю на:" in label(n) for n in tree().iter("node")) else None
    for _ in range(9):
        root=tree()
        chips=[n for n in root.iter("node") if n.get("scrollable")=="true"
               and n.get("package")==PACKAGE and bounds(n)[3]-bounds(n)[1]<300
               and bounds(n)[2]-bounds(n)[0]>500]
        target=button(root,profile)
        if target is not None and chips:
            tx1,_,tx2,_=bounds(target)
            vx1,_,vx2,_=bounds(chips[0])
            # A chip can be present in the accessibility tree while its centre
            # remains outside the horizontally clipped viewport.
            if min(tx2,vx2)-max(tx1,vx1) >= (tx2-tx1)*.65:
                tap_node(target)
                wait("Сегодня работаю на: "+profile)
                return
        if not chips: raise AssertionError("Working locomotive chip row unavailable")
        x1,y1,x2,y2=bounds(chips[0]); y=(y1+y2)//2
        adb("shell","input","swipe",str(x2-30),str(y),str(x1+30),str(y),"450")
        time.sleep(.8)
    raise AssertionError(f"Working locomotive {profile} unavailable")

def shot(name):
    (OUT / f"{name}.png").write_bytes(adb("exec-out","screencap","-p"))

def check_warning():
    open_menu("Настройки")
    for _ in range(12):
        root=tree()
        title=find(root,"Расширенный режим выключен")
        if title is not None:
            y=(bounds(title)[1]+bounds(title)[3])//2
            switches=[n for n in root.iter("node") if n.get("checkable")=="true"
                      and abs((bounds(n)[1]+bounds(n)[3])//2-y)<150]
            if not switches: raise AssertionError("Expanded mode switch unavailable")
            viewports=[bounds(n)[3] for n in root.iter("node") if n.get("scrollable")=="true"
                       and bounds(n)[1]<y<=bounds(n)[3] and bounds(n)[3]-bounds(n)[1]>800]
            if viewports and bounds(switches[0])[3]>min(viewports)-20:
                adb("shell","input","swipe","500","1800","500","500","420")
                time.sleep(.5)
                continue
            tap_node(switches[0])
            break
        adb("shell","input","swipe","500","1800","500","500","420")
        time.sleep(.5)
    else:
        raise AssertionError("Expanded mode setting unavailable")

    root=wait("Пролистайте предупреждение до конца")
    confirm=button(root,"Включить")
    if confirm is None or confirm.get("enabled")!="false":
        raise AssertionError("Warning confirmation must initially be disabled")
    shot("expanded-warning-before-scroll")
    tap_node(confirm)
    if find(wait("Пролистайте предупреждение до конца"),"Включить") is None:
        raise AssertionError("Disabled confirmation closed the warning")
    for _ in range(8):
        root=tree()
        confirm=button(root,"Включить")
        if confirm is not None and confirm.get("enabled")=="true":
            shot("expanded-warning-after-scroll")
            tap_node(confirm)
            wait("Расширенный режим включён")
            return
        scrolls=[n for n in root.iter("node") if n.get("scrollable")=="true"
                 and n.get("package")==PACKAGE and bounds(n)[3]-bounds(n)[1]>250]
        if not scrolls: raise AssertionError("Warning text scroll unavailable")
        x1,y1,x2,y2=bounds(scrolls[-1]); x=(x1+x2)//2
        adb("shell","input","swipe",str(x),str(y2-30),str(x),str(y1+30),"400")
        time.sleep(.5)
    raise AssertionError("Warning confirmation stayed disabled after scrolling")

def check_profile(profile, route):
    working(profile)
    shot(f"{profile}-home")
    open_menu("Локомотивы / атлас")
    wait("Выберите серию и тип материала")
    tap(profile)
    tap("Интерактивный атлас")
    wait("Интерактивная схема "+profile)
    shot(f"{profile}-scheme")
    open_menu("Диагностика")
    wait("Диагностика "+profile)
    shot(f"{profile}-diagnostics")
    open_menu("Приёмка")
    wait("Полный осмотр")
    tap("Полный осмотр")
    wait(route)
    tap(route)
    wait("Шаг 1 из")
    shot(f"{profile}-acceptance")
    open_menu("Главная")
    wait("Сегодня работаю на: "+profile)
    adb("shell","am","force-stop",PACKAGE)
    adb("shell","monkey","-p",PACKAGE,"1")
    wait("Сегодня работаю на: "+profile)
    shot(f"{profile}-restarted")

if __name__ == "__main__":
    apk=os.environ["APK"]
    subprocess.run(["adb","install","-r",apk],check=True,timeout=120)
    adb("logcat","-c")
    adb("shell","am","force-stop",PACKAGE)
    adb("shell","monkey","-p",PACKAGE,"1")
    wait("Железнодорожный помощник")
    try:
        check_profile("ТЭМ2","Начать снаружи")
        check_profile("ТЭМ2У","Начать из кабины")
        check_warning()
    finally:
        (OUT / "crash-logcat.txt").write_bytes(adb("logcat","-d","-b","crash"))
    assert b"Process: ru.railbrake.calculator" not in (OUT / "crash-logcat.txt").read_bytes()
    print("TEM2/TEM2U emulator profiles and expanded mode scroll gate PASS")
