import json
import os
import re
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
PREFER = ("flash", "70b", "mini", "small", "large", "instruct", "llama", "gpt", "mistral", "qwen", "deepseek")

TOPICS = {
    "Physics": ["Mechanics", "Properties of Matter", "Heat and Thermodynamics", "Waves and Sound", "Optics", "Electrostatics", "Current Electricity", "Magnetism and Induction", "Electronics", "Modern Physics"],
    "Biology": ["Cell Biology", "Biochemistry", "Genetics and Heredity", "Evolution", "Ecology and Environment", "Human Biology", "Plant Biology", "Animal Biology", "Classification and Diversity", "Microbiology", "Biotechnology"],
    "Chemistry": ["Atomic Structure", "Periodic Table", "Chemical Bonding", "Stoichiometry", "States of Matter", "Thermochemistry", "Kinetics and Equilibrium", "Acids Bases and Salts", "Electrochemistry", "Organic Chemistry", "Environmental Chemistry"],
    "Mathematics": ["Algebra", "Functions", "Trigonometry", "Calculus", "Geometry", "Statistics and Probability", "Sequences and Series", "Vectors and Matrices", "Coordinate Geometry"],
    "English": ["Reading Comprehension", "Grammar", "Vocabulary", "Cloze and Completion", "Writing and Communication", "Literature"],
}
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

    def ask(self, system, user, json_mode=True, max_tokens=4096):
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
                for model in [m for m in k["models"] if m not in k["bad"]]:
                    out = self.call(k, model, system, user, json_mode, max_tokens)
                    if out is not None:
                        return out
                    if k["dead"]:
                        break
            rounds += 1
            self.log("all keys and models are busy; waiting %ds (round %d)" % (self.pause, rounds))
            time.sleep(self.pause)

    def call(self, k, model, system, user, json_mode, max_tokens):
        body = {"model": model, "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}], "temperature": 0.1, "max_tokens": max_tokens}
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


def enrich(pool, exam, log=print):
    tag_topics(pool, exam, log=log)
    find_groups(pool, exam, log=log)
    fix_format(pool, exam, log=log)
    return exam
