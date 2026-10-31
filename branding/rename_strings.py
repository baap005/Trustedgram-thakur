import re, glob, sys

NEW = "TrustedGram"

# Texts about Telegram the company/service are left alone on purpose.
SKIP = re.compile(
    r"premium|\bads?\b|advertis|sponsor|revenue|moderator|terms|privacy|support|faq|"
    r"stars?\b|business|anti-?spam|gift|fragment|\bton\b|passport|track|telegram\\?'s|"
    r"policy|legal|neither|servers?|cloud|official|verified|translat|bot api|payment|"
    r"credit|subscri|boost", re.I)
LINK = re.compile(r"https?://|t\.me|telegram\.org|telegram\.me|telegram\.dog|@telegram", re.I)
STRING = re.compile(r'(<string name="([^"]+)"[^>]*>)(.*?)(</string>)', re.S)

res_dir = sys.argv[1]
count = {"found": 0, "renamed": 0, "left": 0}

def fix(m):
    key, body = m.group(2), m.group(3)
    if "Telegram" not in body or key.startswith("AppName"):
        return m.group(0)
    count["found"] += 1
    if SKIP.search(body) or LINK.search(body):
        count["left"] += 1
        return m.group(0)
    count["renamed"] += 1
    return m.group(1) + body.replace("Telegram", NEW) + m.group(4)

for path in glob.glob(res_dir + "/values*/strings.xml"):
    text = open(path, encoding="utf-8").read()
    new_text = STRING.sub(fix, text)
    if new_text != text:
        open(path, "w", encoding="utf-8").write(new_text)

print("Strings mentioning Telegram:", count["found"],
      "| renamed:", count["renamed"], "| left alone:", count["left"])
