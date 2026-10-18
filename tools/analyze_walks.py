"""Summarizes a walk export (the history screen's export in developer mode).

    python tools/analyze_walks.py sanpoguide-walks-20261018-1530.json
    python tools/analyze_walks.py export.json --lines        # every line said, for reading the AI's text
    python tools/analyze_walks.py export.json --ai           # every AI call with its prompt and reply
    python tools/analyze_walks.py export.json --walk 1760770000000

Only the standard library. Walks without a detailed record (taken outside developer mode) show
their distance and time only. The format is described in docs/walk-diagnostics.md.
"""

import argparse
import json
import statistics
import sys
from collections import Counter, defaultdict
from datetime import datetime


def hhmm(ms):
    return datetime.fromtimestamp(ms / 1000).strftime("%H:%M:%S")


def minutes(ms):
    return f"{ms / 60000:.1f}分"


def kind_of_lines(events):
    """Pairs each line said with the decision before it ("start" for the greeting, "cue" for a cue's lines)."""
    current = "start"
    for e in events:
        t = e["type"]
        if t == "decision":
            current = e["kind"]
        elif t == "cue":
            current = "cue"
        elif t == "said":
            yield ("summary" if e.get("summary") else current), e


def summarize(walk):
    events = walk.get("diagnostics") or []
    start = datetime.fromtimestamp(walk["startedAt"] / 1000).strftime("%Y-%m-%d %H:%M")
    duration = walk["endedAt"] - walk["startedAt"]
    km = walk["distanceM"] / 1000
    print(f"== {start}  id={walk['id']}  {km:.2f}km  {minutes(duration)}  スポット {len(walk['visits'])} 件")
    if not events:
        print("   （詳しい記録なし）")
        return

    header = events[0] if events[0]["type"] == "walk_start" else {}
    if header:
        print(f"   チャンネル {header.get('station')}  頻度 {header.get('level')}  "
              f"AI {header.get('model') if header.get('aiConfigured') else 'なし'}  アプリ {header.get('app')}")

    # Talk: how often and how far apart.
    lines = list(kind_of_lines(events))
    said_at = [e["t"] for _, e in lines]
    gaps = [b - a for a, b in zip(said_at, said_at[1:])]
    per_km = len(lines) / km if km > 0 else 0
    per_hour = len(lines) / (duration / 3_600_000) if duration > 0 else 0
    print(f"   発言 {len(lines)} 回（{per_km:.1f} 回/km、{per_hour:.1f} 回/時）")
    if gaps:
        print(f"   発言の間隔: 最短 {minutes(min(gaps))}・中央 {minutes(statistics.median(gaps))}・最長 {minutes(max(gaps))}")
    kinds = Counter(k for k, _ in lines)
    print("   種類: " + "、".join(f"{k} {n}" for k, n in kinds.most_common()))
    decisions = Counter(e["kind"] for e in events if e["type"] == "decision")
    busy = sum(1 for e in events if e["type"] == "talk_busy")
    print("   判定: " + "、".join(f"{k} {n}" for k, n in decisions.most_common()) + (f"（話し中で見送り {busy}）" if busy else ""))

    # The AI.
    calls = [e for e in events if e["type"] == "ai"]
    if calls:
        ok = [c for c in calls if c.get("reply") is not None]
        failed = [c for c in calls if c.get("error")]
        flagged = [c for c in calls if c.get("violation")]
        ms = [c["ms"] for c in ok]
        line = f"   AI {len(calls)} 回: 失敗 {len(failed)}・出力の確認で落ちた {len(flagged)}"
        if ms:
            line += f"・応答 中央 {statistics.median(ms) / 1000:.1f}秒・最長 {max(ms) / 1000:.1f}秒"
        print(line)
        lengths = [len(c["reply"]) for c in ok if c["kind"] == "guide"]
        if lengths:
            print(f"   解説の長さ: 中央 {statistics.median(lengths):.0f} 字・最長 {max(lengths)} 字")
    fallbacks = [e for e in events if e["type"] == "producer"]
    if fallbacks:
        print("   代わりの文になった理由: " + "、".join(f"{m} ×{n}" for m, n in Counter(e["message"] for e in fallbacks).most_common(5)))

    # Searches.
    by_host = defaultdict(list)
    for e in events:
        if e["type"] == "overpass":
            by_host[e["host"]].append(e)
    for host, tries in by_host.items():
        good = [e for e in tries if e.get("status") == 200]
        bad = Counter(str(e.get("status") or e.get("error")) for e in tries if e.get("status") != 200)
        ms = [e["ms"] for e in good]
        text = f"   Overpass {host}: {len(tries)} 回・成功 {len(good)}"
        if ms:
            text += f"（中央 {statistics.median(ms) / 1000:.1f}秒）"
        if bad:
            text += "・失敗 " + "、".join(f"{k} ×{n}" for k, n in bad.items())
        print(text)
    search_failed = sum(1 for e in events if e["type"] == "spot_search_failed")
    if search_failed:
        print(f"   スポット検索がすべてのサーバーで失敗: {search_failed} 回")
    for e in (e for e in events if e["type"] == "spot_search"):
        related = [s for s in e["spots"] if s.get("channels")]
        print(f"   {hhmm(e['t'])} スポット検索（{e['channel']['name']}）: スポット {e['spotCount']} 件・施設 {e['facilityCount']} 件"
              + (f"（店: {e['shops']}）" if e.get("shops") else "")
              + (f"・素材のあるスポット: " + "、".join(s["name"] for s in related) if related else ""))
    weather_failed = sum(1 for e in events if e["type"] == "weather_failed")
    if weather_failed:
        print(f"   天気の取得の失敗: {weather_failed} 回")


def print_lines(walk):
    for kind, e in kind_of_lines(walk.get("diagnostics") or []):
        spot = f"［{e['spot']}］" if e.get("spot") else ""
        print(f"{hhmm(e['t'])} {kind:<14} {spot}{e['text']}")


def print_ai(walk):
    for c in (e for e in walk.get("diagnostics") or [] if e["type"] == "ai"):
        print(f"--- {hhmm(c['t'])} {c['kind']} {c['model']} {c['ms']}ms"
              + (f" 失敗: {c['error']}" if c.get("error") else "")
              + (f" 確認で落ちた: {c['violation']}" if c.get("violation") else ""))
        print("[user]\n" + c["user"])
        print("[reply]\n" + (c.get("reply") or ""))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("export")
    parser.add_argument("--walk", type=int, help="この id の散歩だけ")
    parser.add_argument("--lines", action="store_true", help="発言をすべて出す")
    parser.add_argument("--ai", action="store_true", help="AI への問い合わせと答えをすべて出す")
    args = parser.parse_args()

    sys.stdout.reconfigure(encoding="utf-8")
    with open(args.export, encoding="utf-8") as f:
        data = json.load(f)
    if data.get("format") != "sanpoguide-walk-export":
        sys.exit("SanpoGuide の書き出しファイルではありません")

    walks = [w for w in data["walks"] if args.walk is None or w["id"] == args.walk]
    for walk in sorted(walks, key=lambda w: w["startedAt"]):
        if args.lines:
            print(f"== id={walk['id']}")
            print_lines(walk)
        elif args.ai:
            print(f"== id={walk['id']}")
            print_ai(walk)
        else:
            summarize(walk)


if __name__ == "__main__":
    main()
