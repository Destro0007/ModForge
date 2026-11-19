"""Ask a free OpenRouter model to edit a decompiled (apktool) app.

Usage: ai_mod.py <decoded_dir> <instruction_file> <report_file>
Exits non-zero if no edit could be applied, so the workflow fails visibly.
"""
import json
import os
import pathlib
import re
import sys
import urllib.request

src_dir, instr_path, report_path = sys.argv[1:4]
root = pathlib.Path(src_dir).resolve()
instruction = pathlib.Path(instr_path).read_text(encoding="utf-8").strip()
key = os.environ.get("OPENROUTER_API_KEY", "").strip()
if not key:
    sys.exit("OPENROUTER_API_KEY secret is missing in the GitHub repo settings.")

MAX_FILE_CHARS = 15000
MAX_TOTAL_CHARS = 60000

SYSTEM = (
    "You edit decompiled Android apps (apktool output: smali, XML resources). "
    "Reply with ONLY a JSON object: "
    '{"edits":[{"file":"relative/path","find":"exact text","replace":"new text"}],'
    '"summary":"one or two plain sentences about what you changed"}. '
    "Rules: 'find' must be copied exactly from a provided file and appear exactly once in it. "
    "Make the smallest change that does what the user asked. "
    "Only use files that were provided. If the request cannot be done with the provided files, "
    'return {"edits":[],"summary":"<why>"}.'
)


def http_json(url, payload=None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(
        url, data=data,
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=240) as resp:
        return json.load(resp)


def free_models():
    models = http_json("https://openrouter.ai/api/v1/models")["data"]
    free = []
    for m in models:
        pricing = m.get("pricing", {})
        try:
            if float(pricing.get("prompt", 1)) == 0 and float(pricing.get("completion", 1)) == 0:
                free.append(m)
        except (TypeError, ValueError):
            continue
    free.sort(key=lambda m: m.get("context_length", 0), reverse=True)
    return [m["id"] for m in free][:6]


def collect_files():
    chosen = []
    for rel in ("AndroidManifest.xml", "res/values/strings.xml", "res/values/colors.xml",
                "res/values/styles.xml", "res/values/themes.xml"):
        p = root / rel
        if p.exists():
            chosen.append(p)
    words = {w.lower() for w in re.findall(r"[A-Za-z]{4,}", instruction)}
    scored = []
    for p in root.rglob("*"):
        if not p.is_file() or p.suffix not in (".smali", ".xml") or p in chosen:
            continue
        if p.stat().st_size > 40000:
            continue
        try:
            text = p.read_text(encoding="utf-8", errors="ignore").lower()
        except OSError:
            continue
        score = sum(1 for w in words if w in text or w in str(p).lower())
        if score:
            scored.append((score, p))
    scored.sort(key=lambda t: -t[0])
    chosen += [p for _, p in scored[:8]]
    return chosen


def build_prompt(files):
    parts, total = [], 0
    for p in files:
        text = p.read_text(encoding="utf-8", errors="ignore")[:MAX_FILE_CHARS]
        block = f"=== FILE: {p.relative_to(root).as_posix()} ===\n{text}\n"
        if total + len(block) > MAX_TOTAL_CHARS:
            break
        parts.append(block)
        total += len(block)
    return f"User request: {instruction}\n\nProvided files:\n\n" + "\n".join(parts)


def parse_json(text):
    start, end = text.find("{"), text.rfind("}")
    if start == -1 or end == -1:
        raise ValueError("no JSON in reply")
    return json.loads(text[start:end + 1])


def apply_edits(edits):
    applied, problems = [], []
    for e in edits:
        try:
            target = (root / e["file"]).resolve()
            if root not in target.parents or not target.is_file():
                problems.append(f"skipped {e.get('file')}: not a provided file")
                continue
            text = target.read_text(encoding="utf-8")
            if e["find"] == "" or text.count(e["find"]) != 1:
                problems.append(f"skipped {e['file']}: text to replace not found exactly once")
                continue
            target.write_text(text.replace(e["find"], e["replace"], 1), encoding="utf-8")
            applied.append(e["file"])
        except Exception as ex:  # keep going with the other edits
            problems.append(f"skipped an edit: {ex}")
    return applied, problems


def main():
    files = collect_files()
    prompt = build_prompt(files)
    last_note = "No model returned a usable answer."
    for model in free_models():
        print(f"Trying {model}")
        try:
            reply = http_json(
                "https://openrouter.ai/api/v1/chat/completions",
                {
                    "model": model,
                    "temperature": 0.2,
                    "messages": [
                        {"role": "system", "content": SYSTEM},
                        {"role": "user", "content": prompt},
                    ],
                },
            )
            data = parse_json(reply["choices"][0]["message"]["content"])
        except Exception as ex:
            last_note = f"{model} failed: {ex}"
            print(last_note)
            continue
        applied, problems = apply_edits(data.get("edits", []))
        summary = data.get("summary", "")
        if applied:
            lines = [f"Model: {model}", "", summary, "", "Files changed:"]
            lines += [f"- {f}" for f in applied]
            if problems:
                lines += ["", "Notes:"] + [f"- {p}" for p in problems]
            pathlib.Path(report_path).write_text("\n".join(lines) + "\n", encoding="utf-8")
            print("\n".join(lines))
            return
        last_note = f"{model}: {summary or 'no usable edits'} {' '.join(problems)}"
        print(last_note)
    sys.exit("AI could not make this change. Last result: " + last_note)


main()
