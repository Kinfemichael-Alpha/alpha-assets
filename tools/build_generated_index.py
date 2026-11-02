import json, os, re, sys

BASE = "https://huggingface.co/datasets/Kinfemichael/Ethiopian_Exams_and_Books/resolve/main/"
BODY = ("note", "notes", "short_note", "summary", "content", "body", "text", "explanation", "en_explanation", "description", "definition", "am_explanation")


def meta_from_name(name):
    m = re.match(r"(?i)grade[-_ ]?(\d+)[-_ ]+([A-Za-z ]+?)(?:[_-].*)?$", name)
    return (int(m.group(1)), m.group(2).replace("-", " ").strip()) if m else (None, "")


def count_notes(node):
    if isinstance(node, list):
        return sum(count_notes(x) for x in node)
    if isinstance(node, dict):
        if any(node.get(k) for k in BODY):
            return 1
        return sum(count_notes(v) for v in node.values() if isinstance(v, (list, dict)))
    return 0


def main(folder, out_dir=None):
    out_dir = out_dir or folder
    exams, notes = [], []
    for root, _, files in os.walk(folder):
        for fn in sorted(files):
            if not fn.endswith(".json") or fn in ("generates_exams_url.json", "generated_notes_url.json", "generated_exams_url.json"):
                continue
            path = os.path.join(root, fn)
            rel = os.path.relpath(path, folder).replace(os.sep, "/")
            try:
                data = json.load(open(path, encoding="utf-8"))
            except Exception as e:
                print("skip", rel, e)
                continue
            stem = fn[:-5]
            g, subj = meta_from_name(stem)
            if isinstance(data, dict):
                g = data.get("grade", g)
                subj = data.get("subject", subj)
            entry = {"id": stem, "title": (data.get("title") if isinstance(data, dict) else None) or stem.replace("_", " ").replace("-", " "), "grade": g, "subject": subj, "url": BASE + rel}
            qs = data.get("questions") if isinstance(data, dict) else None
            if isinstance(qs, list):
                topics = {}
                for q in qs:
                    k = (q.get("topic") or "Other") if isinstance(q, dict) else "Other"
                    topics[k] = topics.get(k, 0) + 1
                entry.update({"count": len(qs), "topics": topics})
                exams.append(entry)
            else:
                entry["count"] = count_notes(data)
                if entry["count"]:
                    notes.append(entry)
                else:
                    print("skip (neither questions nor notes)", rel)
    json.dump({"schema": 1, "exams": exams}, open(os.path.join(out_dir, "generates_exams_url.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    json.dump({"schema": 1, "notes": notes}, open(os.path.join(out_dir, "generated_notes_url.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(len(exams), "question sets,", len(notes), "note files")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
