import base64
import glob
import json
import os
import re
import subprocess
import tempfile
import time

import requests

BASES = {
    "gemini": "https://generativelanguage.googleapis.com/v1beta/openai",
    "groq": "https://api.groq.com/openai/v1",
    "openai": "https://api.openai.com/v1",
    "openrouter": "https://openrouter.ai/api/v1",
    "mistral": "https://api.mistral.ai/v1",
    "together": "https://api.together.xyz/v1",
    "huggingface": "https://router.huggingface.co/v1",
}
SKIP = ("embed", "whisper", "tts", "audio", "image", "moderation", "dall", "transcribe", "realtime", "rerank", "guard", "vision-preview", "imagen", "veo", "gemma-3n", "aqa")
VISION = ("gemini", "gpt-4o", "gpt-4.1", "gpt-5", "o4", "vision", "-vl", "vl-", "pixtral", "llama-4", "scout", "maverick", "gemma-3", "claude", "mistral-small-3", "mistral-medium", "llava")
PREFER = ("flash", "70b", "mini", "small", "large", "instruct", "llama", "gpt", "mistral", "qwen", "deepseek")

TOPICS = {
    "Physics": ["Mechanics", "Properties of Matter", "Heat and Thermodynamics", "Waves and Sound", "Optics", "Electrostatics", "Current Electricity", "Magnetism and Induction", "Electronics", "Modern Physics"],
    "Biology": ["Cell Biology", "Biochemistry", "Genetics and Heredity", "Evolution", "Ecology and Environment", "Human Biology", "Plant Biology", "Animal Biology", "Classification and Diversity", "Microbiology", "Biotechnology"],
    "Chemistry": ["Atomic Structure", "Periodic Table", "Chemical Bonding", "Stoichiometry", "States of Matter", "Thermochemistry", "Kinetics and Equilibrium", "Acids Bases and Salts", "Electrochemistry", "Organic Chemistry", "Environmental Chemistry"],
    "Mathematics": ["Algebra", "Functions", "Trigonometry", "Calculus", "Geometry", "Statistics and Probability", "Sequences and Series", "Vectors and Matrices", "Coordinate Geometry"],
    "English": ["Reading Comprehension", "Grammar", "Vocabulary", "Cloze and Completion", "Writing and Communication", "Literature"],
}
TRANSCRIBE = ("Mathematics", "Chemistry", "Physics")
FORMAT_SUBJECTS = ("Physics", "Chemistry", "Mathematics")
CUE = re.compile(r"passage|read the following|based on|refer to|questions?\s+\d+\s*(?:-|–|to|and)\s*\d+|the (?:figure|table|diagram|graph|data|information|text|paragraph) (?:above|below)|following (?:figure|table|diagram|graph|data|information|text|paragraph)", re.I)


class Pool:
    def __init__(self, keys, pause=60, log=print):
        self.keys = [dict(k, models=None, dead=False, bad=set()) for k in keys]
        self.pause = pause
        self.log = log
        self.k = 0
        self.m = 0

    def base(self, k):
        return k.get("base") or BASES[k["provider"]]

    def hdr(self, k):
        return {"Authorization": "Bearer " + k["key"], "Content-Type": "application/json"}

    def load_models(self, k):
        r = requests.get(self.base(k) + "/models", headers=self.hdr(k), timeout=30)
        if r.status_code in (401, 403):
            k["dead"] = True
            self.log("key rejected:", k["provider"])
            return []
        r.raise_for_status()
        d = r.json()
        arr = d.get("data") or d.get("models") or []
        ids = []
        for o in arr:
            i = (o.get("id") if isinstance(o, dict) else str(o)) or ""
            i = i.replace("models/", "")
            if i and not any(w in i.lower() for w in SKIP):
                ids.append(i)
        ids.sort(key=lambda x: (-sum(w in x.lower() for w in PREFER), x))
        return ids

    def ask(self, system, user, json_mode=True, max_tokens=4096, images=None):
        rounds = 0
        while True:
            alive = [k for k in self.keys if not k["dead"]]
            if not alive:
                raise RuntimeError("no working API keys")
            for k in alive:
                if k["models"] is None:
                    try:
                        k["models"] = self.load_models(k)
                    except Exception as e:
                        self.log("model list failed:", k["provider"], str(e)[:80])
                        k["models"] = []
                for model in [m for m in k["models"] if m not in k["bad"] and (not images or any(v in m.lower() for v in VISION))]:
                    out = self.call(k, model, system, user, json_mode, max_tokens, images)
                    if out is not None:
                        return out
                    if k["dead"]:
                        break
            if images and not any(any(v in m.lower() for v in VISION) for k in alive for m in (k["models"] or [])):
                raise RuntimeError("no vision-capable model among your keys")
            rounds += 1
            self.log("all keys and models are busy; waiting %ds (round %d)" % (self.pause, rounds))
            time.sleep(self.pause)

    def call(self, k, model, system, user, json_mode, max_tokens, images=None):
        content = user
        if images:
            content = [{"type": "text", "text": user}] + [{"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + b}} for b in images]
        body = {"model": model, "messages": [{"role": "system", "content": system}, {"role": "user", "content": content}], "temperature": 0.1, "max_tokens": max_tokens}
        if json_mode:
            body["response_format"] = {"type": "json_object"}
        for attempt in range(2):
            try:
                r = requests.post(self.base(k) + "/chat/completions", headers=self.hdr(k), json=body, timeout=120)
            except requests.RequestException:
                return None
            if r.status_code == 200:
                try:
                    txt = r.json()["choices"][0]["message"]["content"] or ""
                except Exception:
                    return None
                if txt.strip():
                    return txt
                return None
            if r.status_code in (401, 403):
                k["dead"] = True
                self.log("key rejected:", k["provider"])
                return None
            if r.status_code == 400 and json_mode:
                body.pop("response_format", None)
                json_mode = False
                continue
            if r.status_code in (404, 400, 422):
                k["bad"].add(model)
                return None
            return None
        return None


def parse_json(txt):
    t = txt.strip()
    t = re.sub(r"^```(?:json)?|```$", "", t, flags=re.M).strip()
    try:
        return json.loads(t)
    except Exception:
        m = re.search(r"\{[\s\S]*\}", t)
        if m:
            try:
                return json.loads(m.group(0))
            except Exception:
                return None
    return None


def strip_tags(s):
    return re.sub(r"</?(?:sup|sub)>", "", s)


def squash(s):
    return re.sub(r"\s+", "", s)


def tag_topics(pool, exam, size=15, log=print):
    vocab = TOPICS.get(exam["subject"].split()[0], [])
    qs = [q for q in exam["questions"] if not q.get("topic")]
    sysmsg = "You classify exam questions. Reply with one JSON object only."
    for i in range(0, len(qs), size):
        batch = qs[i : i + size]
        payload = [{"n": q["n"], "question": q["text"][:500], "options": q["options"]} for q in batch]
        user = (
            "Subject: %s. Grade %s.\nAllowed topics: %s.\n"
            "For every question choose exactly one topic from the allowed list (use \"Other\" only if none fits) and write a short specific subtopic (2 to 5 words).\n"
            "Return {\"items\":[{\"n\":number,\"topic\":\"...\",\"subtopic\":\"...\"}]} for all %d questions.\n\n%s"
            % (exam["subject"], exam["grade"], ", ".join(vocab) or "any suitable school topic", len(batch), json.dumps(payload, ensure_ascii=False))
        )
        for _ in range(3):
            d = parse_json(pool.ask(sysmsg, user))
            items = d.get("items") if isinstance(d, dict) else None
            if not items:
                continue
            by = {int(x["n"]): x for x in items if isinstance(x, dict) and str(x.get("n", "")).isdigit()}
            if all(q["n"] in by for q in batch):
                for q in batch:
                    x = by[q["n"]]
                    t = str(x.get("topic", "")).strip()
                    q["topic"] = t if (not vocab or t in vocab) else "Other"
                    q["subtopic"] = str(x.get("subtopic", "")).strip()[:60]
                break
        log("topics", exam["id"], min(i + size, len(qs)), "/", len(qs))


def find_groups(pool, exam, window=14, log=print):
    qs = exam["questions"]
    for idx, q in enumerate(qs):
        if q.get("group") or q.get("leader"):
            continue
        cand = len(q["text"]) > 450 or CUE.search(q["text"]) or (q.get("figure") and idx + 1 < len(qs) and not qs[idx + 1].get("figure") and CUE.search(qs[idx + 1]["text"]))
        if not cand:
            continue
        nxt = qs[idx + 1 : idx + 1 + window]
        if not nxt:
            continue
        payload = {"leader": {"n": q["n"], "text": q["text"][:1500], "figure": q.get("figure")}, "next": [{"n": x["n"], "text": x["text"][:260]} for x in nxt]}
        user = (
            "A question may carry shared material (a reading passage, a table, a figure or a data set) that other questions depend on.\n"
            "Decide whether the leader question holds shared material that the next questions need. If so, list the numbers of the next questions that cannot be answered without it.\n"
            "Return {\"is_leader\":true|false,\"members\":[numbers]} and nothing else.\n\n" + json.dumps(payload, ensure_ascii=False)
        )
        d = parse_json(pool.ask("You analyse exam structure. Reply with one JSON object only.", user))
        if not isinstance(d, dict) or not d.get("is_leader"):
            continue
        allowed = {x["n"] for x in nxt}
        mem = sorted({int(m) for m in d.get("members", []) if str(m).isdigit() and int(m) in allowed})
        if not mem:
            continue
        gid = "g%d" % q["n"]
        q["leader"] = True
        q["group"] = gid
        for x in qs:
            if x["n"] in mem and not x.get("group"):
                x["group"] = gid
        log("group", exam["id"], gid, mem)


def fix_format(pool, exam, size=8, log=print):
    if exam["subject"].split()[0] not in FORMAT_SUBJECTS:
        return
    todo = [q for q in exam["questions"] if "ai_format" not in q["flags"] and re.search(r"[A-Za-z\)]\d|\d-\d|\^|\b\w+[23]\b", q["text"] + " ".join(q["options"].values()) + q["explanation"])]
    for i in range(0, len(todo), size):
        batch = todo[i : i + size]
        payload = [{"n": q["n"], "text": q["text"], "options": q["options"], "explanation": q["explanation"]} for q in batch]
        user = (
            "These exam texts lost their subscripts and superscripts when they were exported (for example 'v02' should be 'v<sub>0</sub><sup>2</sup>', '10-3' should be '10<sup>-3</sup>', 'H2O' should be 'H<sub>2</sub>O', 'sin-1' should be 'sin<sup>-1</sup>').\n"
            "Add <sub></sub> and <sup></sup> tags where they are clearly needed. Do not change, add, remove or reorder any other character. Keep the same JSON structure.\n"
            "Return {\"items\":[{\"n\":number,\"text\":\"...\",\"options\":{\"A\":\"...\"},\"explanation\":\"...\"}]}.\n\n" + json.dumps(payload, ensure_ascii=False)
        )
        d = parse_json(pool.ask("You repair text formatting. Reply with one JSON object only.", user))
        items = d.get("items") if isinstance(d, dict) else None
        if not items:
            continue
        by = {int(x["n"]): x for x in items if isinstance(x, dict) and str(x.get("n", "")).isdigit()}
        for q in batch:
            x = by.get(q["n"])
            if not x:
                continue
            try:
                new_t = x["text"]
                new_o = {k: x["options"][k] for k in q["options"]}
                new_e = x.get("explanation", q["explanation"])
            except Exception:
                continue
            ok = squash(strip_tags(new_t)) == squash(strip_tags(q["text"])) and squash(strip_tags(new_e)) == squash(strip_tags(q["explanation"]))
            ok = ok and all(squash(strip_tags(new_o[k])) == squash(strip_tags(q["options"][k])) for k in q["options"])
            if ok:
                q["text"], q["options"], q["explanation"] = new_t, new_o, new_e
                q["flags"].append("ai_format")
            else:
                q["flags"].append("format_rejected")
        log("format", exam["id"], min(i + size, len(todo)), "/", len(todo))


def page_starts(pdf, count):
    n = int(re.search(r"Pages:\s+(\d+)", subprocess.run(["pdfinfo", pdf], capture_output=True, text=True).stdout).group(1))
    starts = {}
    for pg in range(1, n + 1):
        t = subprocess.run(["pdftotext", "-layout", "-f", str(pg), "-l", str(pg), pdf, "-"], capture_output=True, text=True).stdout
        for m in re.finditer(r"^\s{0,3}(\d{1,3})\.\s", t, re.M):
            q = int(m.group(1))
            if 1 <= q <= count and q not in starts:
                starts[q] = pg
    return starts, n


def render_pages(pdf, pages, dpi=110):
    out = []
    with tempfile.TemporaryDirectory() as d:
        for pg in pages:
            subprocess.run(["pdftoppm", "-r", str(dpi), "-f", str(pg), "-l", str(pg), "-jpeg", "-jpegopt", "quality=82", pdf, os.path.join(d, "p")], check=True)
            f = sorted(glob.glob(os.path.join(d, "p-*.jpg")))[-1]
            out.append(base64.b64encode(open(f, "rb").read()).decode())
            os.remove(f)
    return out


def words(s):
    return {w for w in re.findall(r"[a-z]{4,}", re.sub(r"\\\(.*?\\\)|\$.*?\$", " ", s.lower()))}


def repair_layout(pool, exam, folder, log=print, everything=None):
    key = (exam.get("source") or {}).get("key")
    if not key:
        return
    pdf = os.path.join(folder, key)
    if not os.path.exists(pdf):
        log("missing", pdf)
        return
    if everything is None:
        everything = exam["subject"].split()[0] in TRANSCRIBE
    todo = [q for q in exam["questions"] if ("math_layout" in q["flags"] or everything) and "vision_fixed" not in q["flags"]]
    if not todo:
        return
    starts, npages = page_starts(pdf, exam["count"])
    by_page = {}
    for q in todo:
        pg = starts.get(q["n"])
        if pg:
            by_page.setdefault(pg, []).append(q)
    for pg, qs in sorted(by_page.items()):
        pages = [pg] + ([pg + 1] if pg < npages else [])
        imgs = render_pages(pdf, pages)
        pending = list(qs)
        for attempt in range(2):
            wanted = [q["n"] for q in pending]
            user = (
                "These images are consecutive pages of an exam marking key. Transcribe the questions numbered %s exactly as printed.\n"
                "For each question give: the question text, every option (A, B, C, D and E if present) without the check mark, the correct answer letter (the option marked with a check mark and the 'Answer:' line), and the explanation.\n"
                "Write all mathematics, chemistry formulas and units in LaTeX inside \\( ... \\) (inline). Use fractions, integrals with limits, roots, exponents and subscripts correctly. Do not solve, shorten or improve anything.\n"
                "Return {\"items\":[{\"n\":number,\"text\":\"...\",\"options\":{\"A\":\"...\"},\"answer\":\"A\",\"explanation\":\"...\"}]}." % ", ".join(map(str, wanted))
            )
            try:
                d = parse_json(pool.ask("You transcribe exam pages accurately. Reply with one JSON object only.", user, images=imgs, max_tokens=8192))
            except RuntimeError as e:
                log("layout repair skipped:", e)
                return
            items = d.get("items") if isinstance(d, dict) else None
            by = {int(x["n"]): x for x in (items or []) if isinstance(x, dict) and str(x.get("n", "")).isdigit()}
            still = []
            for q in pending:
                x = by.get(q["n"])
                try:
                    opts = {k: str(v).strip() for k, v in x["options"].items() if str(v).strip()}
                    ans = str(x["answer"]).strip().upper()[:1]
                    txt = str(x["text"]).strip()
                    exp = str(x.get("explanation", "")).strip()
                    ok = sorted(opts) in (list("ABCD"), list("ABCDE")) and ans in opts and txt
                    ok = ok and (not q["answer"] or ans == q["answer"])
                    ok = ok and (not q["options"] or len(opts) == len(q["options"]))
                    a, b = words(txt), words(q["text"])
                    ok = ok and (not b or len(a & b) / len(b) >= 0.5)
                except Exception:
                    ok = False
                if ok:
                    q["flags"] = [f for f in q["flags"] if f not in ("math_layout", "paper_text_differs", "paper_options_differ", "answer_mismatch", "vision_failed") and not f.startswith("option_count")]
                    q["text"], q["options"], q["answer"], q["explanation"] = txt, opts, ans, exp
                    q["flags"].append("vision_fixed")
                else:
                    still.append(q)
            pending = still
            if not pending:
                break
        for q in pending:
            if "vision_failed" not in q["flags"]:
                q["flags"].append("vision_failed")
        log("layout", exam["id"], "page", pg, [q["n"] for q in qs])


def add_extra(pool, exam, size=4, log=print):
    leaders = {}
    for q in exam["questions"]:
        if q.get("leader") and q.get("group"):
            leaders[q["group"]] = q["text"]
    todo = [q for q in exam["questions"] if not q.get("extra") and q["answer"] and "extra_disputed" not in q["flags"]]
    sysmsg = "You are an excellent teacher for Ethiopian grade 12 students preparing for the national exam. Reply with one JSON object only."
    for i in range(0, len(todo), size):
        pending = todo[i : i + size]
        for attempt in range(2):
            payload = []
            for q in pending:
                p = {"n": q["n"], "question": q["text"], "options": q["options"]}
                if q.get("figure"):
                    p["figure"] = q["figure"]
                if q.get("group") and not q.get("leader") and q["group"] in leaders:
                    p["passage"] = leaders[q["group"]]
                payload.append(p)
            user = (
                "Subject: %s. For each question below, first solve it yourself and decide which option is correct, then write an extra explanation for a student who did not understand the question.\n"
                "The extra explanation must be thorough and in Markdown: the core concept in plain words, the full step-by-step working, why the correct option is right, why each wrong option is wrong, and one common mistake to avoid.\n"
                "Write all mathematics and chemistry in LaTeX inside \\( ... \\) or \\[ ... \\]. Use simple English.\n"
                "Return {\"items\":[{\"n\":number,\"answer\":\"A\",\"extra\":\"...\"}]}.\n\n%s" % (exam["subject"], json.dumps(payload, ensure_ascii=False))
            )
            try:
                d = parse_json(pool.ask(sysmsg, user, max_tokens=8192))
            except RuntimeError as e:
                log("extra skipped:", e)
                return
            items = d.get("items") if isinstance(d, dict) else None
            by = {int(x["n"]): x for x in (items or []) if isinstance(x, dict) and str(x.get("n", "")).isdigit()}
            still = []
            for q in pending:
                x = by.get(q["n"])
                try:
                    ok = str(x["answer"]).strip().upper()[:1] == q["answer"] and len(str(x["extra"]).strip()) > 80
                except Exception:
                    ok = False
                if ok:
                    q["extra"] = str(x["extra"]).strip()
                else:
                    still.append(q)
            pending = still
            if not pending:
                break
        for q in pending:
            if "extra_disputed" not in q["flags"]:
                q["flags"].append("extra_disputed")
        log("extra", exam["id"], min(i + size, len(todo)), "/", len(todo))


def refresh_issues(exam):
    exam["issues"] = ["q%d:%s" % (q["n"], f) for q in exam["questions"] for f in q["flags"] if f not in ("ai_format", "vision_fixed", "extra_disputed")]
    return exam


def enrich(pool, exam, log=print, folder=None, extra=True):
    if folder:
        repair_layout(pool, exam, folder, log=log)
    tag_topics(pool, exam, log=log)
    find_groups(pool, exam, log=log)
    fix_format(pool, exam, log=log)
    if extra:
        add_extra(pool, exam, log=log)
    return refresh_issues(exam)
