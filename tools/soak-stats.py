#!/usr/bin/env python3
"""统计写入压测：反复冷启动一个带开屏广告的 App，盯 0.17.82 新加的三处埋点。

为什么单独一个脚本：design.md §8 第 24 条那笔「verified 照打、skipad.db 不涨」的
悬案还差一次复现。0.17.82 补了三条痕迹——

  1. `stats-insert-fail <pkg> 「<detail>」`  写库两次都被拒（SQLiteDatabase.insert 返回 -1）
  2. `排队 <N>ms 才轮到`                    Io 单线程队列前面有任务在堵
  3. `执行 <N>ms`                            某个落盘任务自己就是堵点

这个脚本只做一件事：把「冷启动 → 等开屏 → 收日志」循环跑 N 轮，
每轮统计上面三类痕迹和 `verified` 的条数，最后给一张汇总表。
出现第 1 类说明写被拒、出现 2/3 类说明线程被堵，两者指向完全不同的修法。

用法（Windows，adb 在 PATH 里）：
    python tools/soak-stats.py --rounds 20
    python tools/soak-stats.py --rounds 50 --pkg com.qihoo.cleandroid_cn \
        --activity com.qihoo360.mobilesafe.opti.ui.main.AppEnterActivity --serial ac250e7d

注意：每轮之间必须切一次别的应用再启动目标，否则 `prev == 当前包`，
开屏窗口根本不会打开（日志里只有 `wsc ... fresh=false`，引擎一次都不扫）。
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import time

DEFAULT_PKG = "com.qihoo.cleandroid_cn"
DEFAULT_ACT = "com.qihoo360.mobilesafe.opti.ui.main.AppEnterActivity"
ENGINE_TAG = "DaxiaojieEngine"

# 运行时由 --adb / PATH / SDK 目录三步解析出来，模块级先给个默认值，
# 避免任何提前调用拿到未定义名字
ADB = "adb"

RE_VERIFIED = re.compile(r"verified (\S+) 「")
RE_INSERT_FAIL = re.compile(r"stats-insert-fail")
RE_SLOW_WAIT = re.compile(r"排队 (\d+)ms 才轮到")
RE_SLOW_RUN = re.compile(r"执行 (\d+)ms")


def resolve_adb(explicit: str) -> str:
    """找 adb：显式指定 > PATH > Android SDK 默认位置。

    Windows 上 Python 的 PATH 经常和 PowerShell 不是同一份，直接调 "adb" 会
    FileNotFoundError，所以这里退一步去 SDK 目录找。
    """
    if explicit:
        return explicit
    found = shutil.which("adb")
    if found:
        return found
    guess = os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe")
    if os.path.exists(guess):
        return guess
    return "adb"


def adb(serial: str, *args: str, text: bool = True) -> str:
    cmd = [ADB] + (["-s", serial] if serial else []) + list(args)
    # 不用 text=True：logcat 里混着 GBK/控制字符，按默认编码解会整段抛
    # UnicodeDecodeError（曾把整轮的引擎日志吞成 0 条）
    out = subprocess.run(cmd, capture_output=True)
    raw = out.stdout.decode("utf-8", "replace") if text else ""
    if out.returncode != 0 and text:
        # 不在这里抛：force-stop 之类偶尔返回非零，不该中断整轮压测
        return raw + out.stderr.decode("utf-8", "replace")
    return raw


def one_round(serial: str, pkg: str, act: str, wait: float) -> dict:
    # 1) 切到设置：让下一次启动是「换包」，开屏窗口才会开
    adb(serial, "shell", "am start -a android.settings.SETTINGS")
    time.sleep(1.2)
    # 2) 杀掉目标 App，保证是真冷启动
    adb(serial, "shell", f"am force-stop {pkg}")
    time.sleep(0.6)
    adb(serial, "logcat", "-c")
    adb(serial, "shell", f"am start -n {pkg}/{act}")
    time.sleep(wait)

    log = adb(serial, "logcat", "-d")
    lines = [ln for ln in log.splitlines() if ENGINE_TAG in ln]
    verified = [ln for ln in lines if RE_VERIFIED.search(ln)]
    insert_fail = [ln for ln in lines if RE_INSERT_FAIL.search(ln)]
    slow_wait = [int(m.group(1)) for ln in lines for m in [RE_SLOW_WAIT.search(ln)] if m]
    slow_run = [int(m.group(1)) for ln in lines for m in [RE_SLOW_RUN.search(ln)] if m]

    return {
        "verified": len(verified),
        "insert_fail": len(insert_fail),
        "slow_wait": slow_wait,
        "slow_run": slow_run,
        "engine_lines": len(lines),
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="大小姐统计写入压测")
    ap.add_argument("--rounds", type=int, default=10)
    ap.add_argument("--pkg", default=DEFAULT_PKG)
    ap.add_argument("--activity", default=DEFAULT_ACT)
    ap.add_argument("--serial", default="")
    ap.add_argument("--adb", default="", help="adb 路径，默认自动查找")
    ap.add_argument("--wait", type=float, default=9.0, help="每轮启动后等多久再收日志（秒）")
    args = ap.parse_args()

    global ADB
    ADB = resolve_adb(args.adb)

    print(f"压测 {args.rounds} 轮：{args.pkg}")
    print(f"{'轮次':<4}{'verified':<10}{'写失败':<8}{'排队>3s':<10}{'执行>5s':<10}{'引擎日志':<8}")
    total = {"verified": 0, "insert_fail": 0, "slow_wait": 0, "slow_run": 0}
    worst_wait = 0
    worst_run = 0

    for i in range(1, args.rounds + 1):
        r = one_round(args.serial, args.pkg, args.activity, args.wait)
        total["verified"] += r["verified"]
        total["insert_fail"] += r["insert_fail"]
        total["slow_wait"] += len(r["slow_wait"])
        total["slow_run"] += len(r["slow_run"])
        if r["slow_wait"]:
            worst_wait = max(worst_wait, max(r["slow_wait"]))
        if r["slow_run"]:
            worst_run = max(worst_run, max(r["slow_run"]))
        print(
            f"{i:<5}{r['verified']:<10}{r['insert_fail']:<9}"
            f"{len(r['slow_wait']):<10}{len(r['slow_run']):<10}{r['engine_lines']:<8}"
        )

    print("-" * 52)
    print(f"合计：verified {total['verified']} 次，写被拒 {total['insert_fail']} 次，"
          f"排队超 3 秒 {total['slow_wait']} 次，执行超 5 秒 {total['slow_run']} 次")
    if total["insert_fail"]:
        print("→ 出现 stats-insert-fail：抓 logcat 里那几行，连同 SQLiteDatabase 的报错一起看")
    if total["slow_wait"] or total["slow_run"]:
        print(f"→ 线程被堵：最长排队 {worst_wait}ms，最长单任务 {worst_run}ms")
    if not any(total.values()):
        print("→ 本轮没撞到失效，也没撞到跳过（可能这些 App 今天没投开屏）")
    return 1 if total["insert_fail"] else 0


if __name__ == "__main__":
    sys.exit(main())
