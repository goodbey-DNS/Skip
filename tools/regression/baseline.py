# -*- coding: utf-8 -*-
"""
开屏跳过回归测试：只测内置通用判定，不掺任何导入规则。

用法（在工程根目录）：
    python tools/regression/baseline.py                 # 默认 5 个 App，每个 3 轮
    python tools/regression/baseline.py --rounds 5
    python tools/regression/baseline.py --pkgs tv.danmaku.bili,com.sina.weibo

它会做四件事：
    1. 校验设备、App 是否装好、无障碍服务是否绑着
    2. 清空统计（--keep-stats 可跳过）
    3. 逐个冷启动目标 App，每次等 --wait 秒
    4. 回读首页统计，打印每个 App 的命中次数

为什么必须清空统计：统计是累计的，不清空就看不出「这一轮跳了几次」。

为什么不用引擎诊断读数：读它要 dump 界面，dump 会抢走无障碍通道、让服务重建实例，
内存日志在看见之前就没了。0.17.29 起引擎日志会落盘，要看细节走
「设置 → 数据与维护 → 导出日志」，查里面的 match / miss 行。

已知限制：统计只记「成功跳过」，不记「匹配到但没点」。
"""
import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.noteatoranges.skipad"
SVC_PART = "SkipAccessibilityService"
SVC = PKG + "/" + PKG + ".SkipAccessibilityService"

DEFAULT_PKGS = [
    ("tv.danmaku.bili", "哔哩哔哩"),
    ("com.sina.weibo", "微博"),
    ("com.taobao.taobao", "淘宝"),
    ("com.jingdong.app.mall", "京东"),
    ("com.autonavi.minimap", "高德"),
]

ADB_CANDIDATES = [
    os.environ.get("ADB", ""),
    os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"),
    r"C:\Android\platform-tools\adb.exe",
    "adb",
]


def find_adb():
    for c in ADB_CANDIDATES:
        if not c:
            continue
        if c == "adb":
            try:
                subprocess.run(["adb", "version"], capture_output=True, check=True)
                return "adb"
            except Exception:
                continue
        if os.path.exists(c):
            return c
    sys.exit("找不到 adb：设 ADB 环境变量，或改脚本里的 ADB_CANDIDATES")


ADB = find_adb()


def sh(args, timeout=120):
    p = subprocess.run([ADB] + args, capture_output=True, timeout=timeout)
    return p.stdout.decode("utf-8", "replace") + p.stderr.decode("utf-8", "replace")


def dump():
    txt = sh(["exec-out", "uiautomator", "dump", "/dev/tty"], 90)
    m = re.search(r"<\?xml.*</hierarchy>", txt, re.S)
    if not m:
        return []
    nodes = []
    for n in ET.fromstring(m.group(0)).iter("node"):
        b = n.get("bounds", "")
        mm = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
        if not mm:
            continue
        x1, y1, x2, y2 = map(int, mm.groups())
        nodes.append({
            "text": n.get("text", ""),
            "rid": n.get("resource-id", "").split("/")[-1],
            "cx": (x1 + x2) // 2,
            "cy": (y1 + y2) // 2,
        })
    return nodes


def tap(n):
    sh(["shell", "input", "tap", str(n["cx"]), str(n["cy"])])


def find(nodes, rid=None, text=None):
    for n in nodes:
        if rid and n["rid"] == rid:
            return n
        if text and n["text"] == text:
            return n
    return None


def preflight(pkgs):
    out = sh(["shell", "pm", "list", "packages"])
    installed = set(l.replace("package:", "").strip() for l in out.splitlines() if l.startswith("package:"))
    if PKG not in installed:
        sys.exit("设备上没装 SkipAd（%s）" % PKG)
    missing = [p for p, _ in pkgs if p not in installed]
    if missing:
        print("跳过未安装的 App：%s" % ", ".join(missing))
    acc = sh(["shell", "settings", "get", "secure", "enabled_accessibility_services"])
    if SVC_PART not in acc:
        print("警告：无障碍服务没启用，测出来的 0 次不代表引擎有问题")
    return [(p, n) for p, n in pkgs if p in installed]


def open_main(home=True):
    sh(["shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1"])
    time.sleep(5.5)
    if home:
        nodes = dump()
        h = find(nodes, rid="tab_home")
        if h:
            tap(h)
            # 这个 App 的 tab 切换实测要 5–6 秒才完成，等短了读到的还是上一页
            time.sleep(6.0)


def rebind_service():
    """
    界面转储会抢走无障碍通道，服务被踢后要重绑。

    不重绑就直接跑冷启动，测出来的是「服务没连上时的 0 次」，
    而不是引擎本身的表现 —— 这一点不写进脚本里，很容易得出错误结论。
    """
    sh(["shell", "settings", "put", "secure", "enabled_accessibility_services", ""])
    time.sleep(1.2)
    sh(["shell", "settings", "put", "secure", "enabled_accessibility_services", SVC])
    time.sleep(2.5)


def clear_stats():
    """UI 路径清空统计：首页 → 设置 → 数据与维护 → 清空统计数据 → 确认清空"""
    open_main()
    nodes = []
    for _ in range(4):
        nodes = dump()
        if find(nodes, text="数据与维护"):
            break
        t = find(nodes, rid="tab_settings")
        if t:
            tap(t)
        time.sleep(5.5)
    m = find(nodes, text="数据与维护")
    if not m:
        print("没能进「数据与维护」页，统计未清空（继续跑，看的是增量）")
        return
    tap(m)
    time.sleep(3.0)
    c = find(dump(), text="清空统计数据")
    if not c:
        print("没找到「清空统计数据」")
        return
    tap(c)
    time.sleep(2.0)
    nodes = dump()
    yes = find(nodes, text="确认清空") or find(nodes, text="清空")
    if yes:
        tap(yes)
        time.sleep(2.0)
        print("统计已清空")


def read_stats():
    open_main(home=True)
    nodes = dump()
    texts = [n["text"] for n in nodes if n["text"]]
    joined = " | ".join(texts)
    m = re.search(r"累计已跳过\s*(\d+)\s*次", joined)
    total = int(m.group(1)) if m else -1
    per = {}
    for i, t in enumerate(texts):
        if re.fullmatch(r"\d+\s*次", t) and i > 0:
            per[texts[i - 1]] = t
    return total, per, joined


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rounds", type=int, default=3, help="每个 App 冷启动几次")
    ap.add_argument("--wait", type=float, default=6.0, help="每次冷启动等几秒")
    ap.add_argument("--pkgs", default="", help="逗号分隔的包名，默认用脚本内的 5 个")
    ap.add_argument("--keep-stats", action="store_true", help="不清空统计（看增量）")
    args = ap.parse_args()

    pkgs = DEFAULT_PKGS
    if args.pkgs:
        names = [x.strip() for x in args.pkgs.split(",") if x.strip()]
        label = dict(DEFAULT_PKGS)
        pkgs = [(p, label.get(p, p)) for p in names]

    pkgs = preflight(pkgs)
    if not pkgs:
        sys.exit("没有可测的目标")

    # 屏幕常亮：测到一半息屏，冷启动就全废了
    sh(["shell", "svc", "power", "stayon", "true"])

    if not args.keep_stats:
        clear_stats()
        rebind_service()

    before, _, _ = read_stats()
    # 读基线已经把服务踢了，这里补一次重绑，否则第一轮全废
    rebind_service()
    print("基线累计跳过：%s 次" % before)

    print("\n开始：%d 个 App × %d 轮" % (len(pkgs), args.rounds))
    for r in range(1, args.rounds + 1):
        for pkg, _ in pkgs:
            sh(["shell", "am", "force-stop", pkg])
            time.sleep(0.6)
            sh(["shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1"])
            time.sleep(args.wait)
            sh(["shell", "input", "keyevent", "3"])
            time.sleep(1.5)
        print("  第 %d 轮完成" % r)

    after, per, _ = read_stats()
    delta = (after - before) if (after >= 0 and before >= 0) else None
    print("\n结果：累计 %s 次（基线 %s），本轮新增 %s 次" % (
        after, before, delta if delta is not None else "?"))
    if per:
        for k, v in per.items():
            print("  %-16s %s" % (k, v))
    print("\n统计只记「成功跳过」。要看「匹配到但没点」或「为什么没点」，"
          "去 设置 → 数据与维护 → 导出日志，查 match / miss 行。")


if __name__ == "__main__":
    main()
