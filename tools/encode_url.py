import base64
import hashlib
import sys

PREFIX = "AL1"
KEY = hashlib.sha256("alpha-ai|url|v1|k7Qm".encode("utf-8")).digest()


def _mix(data):
    return bytes((b ^ KEY[i % len(KEY)] ^ ((i * 31 + 7) & 0xFF)) & 0xFF for i, b in enumerate(data))


def _sum(data):
    s = 0x5A5A
    for b in data:
        s = (s * 31 + b) & 0xFFFF
    return s


def encode(url):
    p = url.encode("utf-8")
    s = _sum(p)
    body = _mix(p + bytes([(s >> 8) & 0xFF, s & 0xFF]))
    return PREFIX + base64.urlsafe_b64encode(body).decode("ascii").rstrip("=")


def decode(text):
    t = "".join(text.split())
    if not t.startswith(PREFIX):
        return None
    b64 = t[len(PREFIX):]
    raw = _mix(base64.urlsafe_b64decode(b64 + "=" * (-len(b64) % 4)))
    p, s = raw[:-2], (raw[-2] << 8) | raw[-1]
    return p.decode("utf-8") if _sum(p) == s else None


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: python encode_url.py <https-url>   (or: python encode_url.py --decode <code>)")
    elif sys.argv[1] == "--decode":
        print(decode(sys.argv[2]))
    else:
        code = encode(sys.argv[1])
        print(code)
        assert decode(code) == sys.argv[1]
