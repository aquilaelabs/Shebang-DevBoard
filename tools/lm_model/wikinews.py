#!/usr/bin/env python3
"""Extract plain sentences from an English Wikinews dump (CC BY 2.5), one per line.

Usage: wikinews.py enwikinews-latest-pages-articles.xml.bz2 OUT.txt

Only published articles (main namespace, not redirects, marked {{publish}} or in Category:Published) are
read. Templates, tables, references, files, HTML and headings are dropped, links keep their text, and the
rest is cut into sentences of 3 to 60 words. Dates and datelines ("Monday, May 5, 2008") at the start are
dropped. The output goes beside the Common Voice sentence files (tools/build_ngrams.py --cv reads every .txt
in that folder; tools/lm_model/prep.py likewise).
"""
import bz2
import re
import sys
import xml.etree.ElementTree as ET

NS = "{http://www.mediawiki.org/xml/export-0.11/}"


def strip_nested(text, open_, close):
    out = []
    depth = 0
    i = 0
    while i < len(text):
        if text.startswith(open_, i):
            depth += 1
            i += len(open_)
        elif depth and text.startswith(close, i):
            depth -= 1
            i += len(close)
        else:
            if depth == 0:
                out.append(text[i])
            i += 1
    return "".join(out)


LINK = re.compile(r"\[\[(?:[^\]|]*\|)?([^\]]*)\]\]")
EXT = re.compile(r"\[https?://\S+\s*([^\]]*)\]")
SENT = re.compile(r"(?<=[.!?])[\"')\]]?\s+(?=[\"'(]?[A-Z])")
DATELINE = re.compile(r"^(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday),\s+\w+\s+\d+,\s+\d{4}\s*")


def clean(wikitext):
    t = re.sub(r"<!--.*?-->", " ", wikitext, flags=re.S)
    t = re.sub(r"<ref[^>/]*/>", " ", t)
    t = re.sub(r"<ref.*?</ref>", " ", t, flags=re.S)
    t = strip_nested(t, "{{", "}}")
    t = strip_nested(t, "{|", "|}")
    t = re.sub(r"\[\[(File|Image|Category|w:Category|commons|:?[a-z]{2,3}):[^\]]*\]\]", " ", t, flags=re.I)
    for _ in range(2):
        t = LINK.sub(r"\1", t)
    t = EXT.sub(r"\1", t)
    t = re.sub(r"<[^>]+>", " ", t)
    t = re.sub(r"'{2,}", "", t)
    lines = []
    for line in t.split("\n"):
        line = line.strip()
        if not line or line.startswith(("=", "*", "#", ":", ";", "|", "!")):
            continue
        lines.append(line)
    return " ".join(lines)


def main():
    src, out = sys.argv[1], sys.argv[2]
    pages = kept = sentences = words = 0
    with bz2.open(src, "rb") as fh, open(out, "w", encoding="utf-8") as w:
        for _event, el in ET.iterparse(fh):
            if el.tag != NS + "page":
                continue
            pages += 1
            ns = el.findtext(NS + "ns")
            text = el.findtext(f"{NS}revision/{NS}text") or ""
            redirect = el.find(NS + "redirect") is not None
            el.clear()
            if ns != "0" or redirect:
                continue
            if "{{publish" not in text.lower() and "category:published" not in text.lower():
                continue
            kept += 1
            body = DATELINE.sub("", clean(text))
            for s in SENT.split(body):
                s = s.strip()
                n = len(s.split())
                # A removed template leaves a hole (" ,", two spaces, "()"): the words around it never met.
                holed = " ," in s or "  " in s or "()" in s or " ." in s or "( " in s
                if 3 <= n <= 60 and not holed and re.match(r"[\"'(]?[A-Z]", s) and s[-1] in ".!?\"')":
                    w.write(s + "\n")
                    sentences += 1
                    words += n
    print(f"{out}: {kept} published articles of {pages} pages, {sentences} sentences, {words} words")


if __name__ == "__main__":
    main()
