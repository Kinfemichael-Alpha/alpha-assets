import json
import os
import re
import subprocess
import sys

NOISE = re.compile(r"Temari\.et|MARKING\s*KEY|M\s*AR\s*K\s*I\s*N\s*G|Time allowed|Generated on|^\s*Grade\s+\d+\s*[·.]|Name\s+Grade\s+ID No|entrance exam, round|—\s*END OF|E\s*N\s*D\s*O\s*F\s*E\s*X\s*A\s*M", re.I)
QSTART = re.compile(r"^\s{0,3}(\d{1,3})\.\s+(\S.*)$")
OPT = re.compile(r"^\s+(✓\s*)?([A-E])\.\s*(✓\s*)?(.*)$")
MARKS = re.compile(r"\s*\((\d+)\)\s*$")
ANS = re.compile(r"^\s+Answer:\s*([A-E])\b")
FIG_OPEN = re.compile(r"^\s*\[Figure:")
NAME = re.compile(r"Grade-(\d+)-(.+?)_(\d{4})-EC(?:-Round-(\d+))?(?:_(.*))?$")

SUP_RULES = [
    (re.compile(r"(×\s*10)(-?\d+)\b"), r"\1<sup>\2</sup>"),
    (re.compile(r"\b(m/s|cm/s|km/s|rad/s)([23])\b"), r"\1<sup>\2</sup>"),
    (re.compile(r"\b(kg/m|g/cm|g/m|kg/cm)([23])\b"), r"\1<sup>\2</sup>"),
    (re.compile(r"\b(mm|cm|km|m)([23])\b(?!\.\d)"), r"\1<sup>\2</sup>"),
]


def pdf_text(path):
    return subprocess.run(["pdftotext", "-layout", path, "-"], capture_output=True, text=True, check=True).stdout


def split_options(line):
    parts = re.split(r"\s{3,}(?=(?:✓\s*)?[A-E]\.\s)", line.rstrip())
    return [p for p in parts if p.strip()]


def clean(s):
    s = s.replace("​", "").replace(" ", " ")
    return re.sub(r"\s+", " ", s).strip()


def parse(text):
    questions = []
    cur = None
    mode = "q"
    in_fig = False
    started = False
    for raw in text.split("\n"):
        line = raw.rstrip()
        if "END OF MARKING KEY" in line or re.search(r"Generated on .*Temari", line) or re.search(r"E\s*N\s*D\s*O\s*F\s*E\s*X\s*A\s*M", line):
            break
        if not started:
            if QSTART.match(line) and QSTART.match(line).group(1) == "1":
                started = True
            else:
                continue
        if not line.strip():
            continue
        if NOISE.search(line) and not QSTART.match(line):
            continue
        if in_fig:
            cur["figure"] += " " + line.strip()
            if "]" in line:
                in_fig = False
                cur["figure"] = clean(cur["figure"]).rstrip("]").strip()
            continue
        m = QSTART.match(line)
        if m and (cur is None or int(m.group(1)) == cur["n"] + 1) and mode != "opt_open":
            body = m.group(2)
            cur = {"n": int(m.group(1)), "text": "", "options": {}, "answer": None, "explanation": "", "marks": 1, "figure": None, "_last": None}
            questions.append(cur)
            mm = MARKS.search(body)
            if mm:
                cur["marks"] = int(mm.group(1))
                body = body[: mm.start()]
            cur["text"] = body.strip()
            mode = "q"
            continue
        if cur is None:
            continue
        a = ANS.match(line)
        if a:
            cur["answer"] = a.group(1)
            mode = "exp"
            continue
        if FIG_OPEN.match(line):
            cur["figure"] = line.strip().lstrip("[").replace("Figure:", "", 1).strip()
            if "]" in line:
                cur["figure"] = clean(cur["figure"]).rstrip("]").strip()
            else:
                in_fig = True
            continue
        if mode in ("q", "opt") and OPT.match(line):
            mode = "opt"
            for piece in split_options(line):
                om = OPT.match(" " + piece.strip()) if not piece.startswith(" ") else OPT.match(piece)
                if not om:
                    continue
                mark = bool(om.group(1) or om.group(3))
                letter = om.group(2)
                val = om.group(4)
                mm = MARKS.search(val)
                if mm and not cur["options"]:
                    pass
                cur["options"][letter] = {"text": val.strip(), "ok": mark}
                cur["_last"] = letter
            continue
        if mode == "q":
            mm = MARKS.fullmatch(line.strip())
            if mm:
                cur["marks"] = int(mm.group(1))
                continue
            seg = line.strip()
            mm = MARKS.search(seg)
            if mm and re.search(r"\s{3,}\(\d+\)\s*$", line):
                cur["marks"] = int(mm.group(1))
                seg = seg[: mm.start()]
            cur["text"] += " " + seg
        elif mode == "opt":
            if cur["_last"]:
                cur["options"][cur["_last"]]["text"] += " " + line.strip()
        elif mode == "exp":
            cur["explanation"] += " " + line.strip()
    out = []
    for q in questions:
        for k, v in q["options"].items():
            if "✓" in v["text"]:
                v["ok"] = True
                v["text"] = v["text"].replace("✓", "")
        opts = {k: clean(v["text"]) for k, v in q["options"].items()}
        marked = [k for k, v in q["options"].items() if v["ok"]]
        q["text"] = clean(q["text"])
        q["explanation"] = clean(q["explanation"])
        q["options"] = opts
        q["marked"] = marked
        q.pop("_last", None)
        out.append(q)
    return out


ENT = {"&implies;": "⇒", "&rarr;": "→", "&larr;": "←", "&deg;": "°", "&plusmn;": "±", "&times;": "×", "&le;": "≤", "&ge;": "≥", "&ne;": "≠", "&approx;": "≈", "&micro;": "µ", "&Delta;": "Δ", "&delta;": "δ", "&theta;": "θ", "&pi;": "π", "&Omega;": "Ω", "&omega;": "ω", "&alpha;": "α", "&beta;": "β", "&gamma;": "γ", "&lambda;": "λ", "&mu;": "µ", "&rho;": "ρ", "&sigma;": "σ", "&epsilon;": "ε"}


def sup_fix(s):
    s = re.sub(r"&vec;(\w)", r"$\\vec{\1}$", s)
    for k, v in ENT.items():
        s = s.replace(k, v)
    for rx, rep in SUP_RULES:
        s = rx.sub(rep, s)
    return s


def build(base, paper_path, key_path):
    m = NAME.match(base)
    grade, subject, year, rnd = (int(m.group(1)), m.group(2).replace("-", " "), int(m.group(3)), m.group(4)) if m else (None, base, None, None)
    key = parse(pdf_text(key_path)) if key_path else []
    paper = parse(pdf_text(paper_path)) if paper_path else []
    src = key or paper
    flags_all = []
    by_paper = {q["n"]: q for q in paper}
    questions = []
    for q in src:
        flags = []
        ans = q["answer"]
        if key:
            if len(q["marked"]) != 1:
                flags.append("check_mark")
            elif ans and q["marked"][0] != ans:
                flags.append("answer_mismatch")
            if not ans and len(q["marked"]) == 1:
                ans = q["marked"][0]
        else:
            flags.append("no_key")
        if len(q["options"]) != 4:
            flags.append("option_count_%d" % len(q["options"]))
        p = by_paper.get(q["n"])
        if key and p and re.sub(r"\s+", "", p["text"]) != re.sub(r"\s+", "", q["text"]):
            flags.append("paper_text_differs")
        if key and p and {k: re.sub(r"\s+", "", v) for k, v in p["options"].items()} != {k: re.sub(r"\s+", "", v) for k, v in q["options"].items()}:
            flags.append("paper_options_differ")
        if not q["text"]:
            flags.append("empty_text")
        vals = list(q["options"].values())
        if len(set(vals)) != len(vals):
            flags.append("duplicate_options")
        blob = q["text"] + " ".join(vals) + q["explanation"]
        if re.search(r"&\w+;", sup_fix(blob)):
            flags.append("unknown_entity")
        if key and not q["explanation"]:
            flags.append("no_explanation")
        item = {
            "n": q["n"],
            "text": sup_fix(q["text"]),
            "options": {k: sup_fix(v) for k, v in q["options"].items()},
            "answer": ans,
            "explanation": sup_fix(q["explanation"]),
            "marks": q["marks"],
            "figure": q["figure"],
            "topic": None,
            "group": None,
            "leader": False,
            "flags": flags,
        }
        questions.append(item)
    nums = [q["n"] for q in questions]
    issues = []
    if nums != list(range(1, len(nums) + 1)):
        issues.append("numbering_gap")
    for q in questions:
        for f in q["flags"]:
            issues.append("q%d:%s" % (q["n"], f))
    tm = re.search(r"Time allowed:\s*(\d+)\s*minutes", pdf_text(key_path or paper_path))
    exam = {
        "schema": 1,
        "id": re.sub(r"[^a-z0-9]+", "-", base.lower()).strip("-"),
        "grade": grade,
        "subject": subject,
        "year_ec": year,
        "round": int(rnd) if rnd else None,
        "count": len(questions),
        "total_marks": sum(q["marks"] for q in questions),
        "time_minutes": int(tm.group(1)) if tm else None,
        "issues": issues,
        "enriched": False,
        "questions": questions,
    }
    return exam, issues


def pair_files(folder):
    groups = {}
    for dp, _, fs in os.walk(folder):
        for f in fs:
            if not f.lower().endswith(".pdf"):
                continue
            stem = f[:-4]
            m = re.match(r"(.+?)(?:_Entrance-Exam)?_(Answer-Key|Question-Paper)$", stem)
            if not m:
                continue
            groups.setdefault((dp, m.group(1)), {})[m.group(2)] = os.path.join(dp, f)
    return groups


def main(folder, out_dir=None, delete_pdfs=False):
    report = []
    for (dp, base), files in sorted(pair_files(folder).items()):
        try:
            exam, issues = build(base, files.get("Question-Paper"), files.get("Answer-Key"))
        except Exception as e:
            report.append((base, "FAILED " + repr(e)))
            continue
        target = out_dir or dp
        os.makedirs(target, exist_ok=True)
        path = os.path.join(target, base + ".json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(exam, fh, ensure_ascii=False, indent=1)
        ok = not issues
        report.append((base, "OK %d questions" % exam["count"] if ok else "REVIEW %d issues: %s" % (len(issues), ", ".join(issues[:8]))))
        if ok and delete_pdfs:
            for p in files.values():
                os.remove(p)
    for b, r in report:
        print(b, "->", r)
    return report


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
