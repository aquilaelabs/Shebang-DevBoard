#!/usr/bin/env python3
"""Turn permissively licensed technical documentation into plain sentences for tools/build_ngrams.py --tech.

Usage: tools/tech_corpus.py OUT.txt NAME=DIR [NAME=DIR ...]

Each DIR is searched for Markdown (.md), reStructuredText (.rst) and AsciiDoc (.adoc) files. Code blocks,
front matter, directives, tables, HTML and link targets are dropped; inline markup keeps its text, so
"run `kubectl apply`" stays a sentence about kubectl. Paragraphs are split into sentences; one that is too
short or mostly not words is skipped. Writes one sentence per line, prefixed by its source NAME and a tab, so
counts can be traced back.

The sources used for the shipped model, with their licences, are listed in THIRD_PARTY_NOTICES.md
("Technical documentation"). Only counts derived from the text reach the app; none of the text does.
"""
import os
import re
import sys

FENCE = re.compile(r"^\s*(```|~~~)")
ADOC_DELIM = re.compile(r"^(----|\.\.\.\.|====|\+\+\+\+|\|===|////)\s*$")
RST_DIRECTIVE = re.compile(r"^\s*\.\.\s+[\w:-]+::")
RST_UNDERLINE = re.compile(r"^\s*([=\-~^\"'`#*+])\1{2,}\s*$")
SHORTCODE = re.compile(r"\{\{[<%].*?[%>]\}\}")
HTML_TAG = re.compile(r"<[^>\n]+>")
MD_IMAGE = re.compile(r"!\[[^\]]*\]\([^)]*\)")
MD_LINK = re.compile(r"\[([^\]]+)\]\([^)]*\)")
MD_REF_LINK = re.compile(r"\[([^\]]+)\]\[[^\]]*\]")
RST_ROLE = re.compile(r":[\w:-]+:`([^`<]*?)(?:\s*<[^>]*>)?`")
RST_LINK = re.compile(r"`([^`<]+?)\s*<[^>]+>`_+")
ADOC_LINK = re.compile(r"(?:link:|https?://)\S*?\[([^\]]*)\]")
ADOC_XREF = re.compile(r"<<[^,>]*,?([^>]*)>>")
URL = re.compile(r"https?://\S+")
INLINE_MARKS = re.compile(r"[`*_]{1,3}")
LIST_MARK = re.compile(r"^\s*([-*+]|\d+[.)]|[a-z][.)])\s+")
SENTENCE_END = re.compile(r"(?<=[.!?])\s+(?=[A-Z\"'(])")
WORD = re.compile(r"[A-Za-z]+")


def paragraphs(path):
    """The prose paragraphs of one file, markup still inside."""
    ext = os.path.splitext(path)[1]
    with open(path, encoding="utf-8", errors="replace") as fh:
        lines = fh.read().split("\n")
    out, para = [], []
    i = 0
    # Front matter (Markdown) and the document header (AsciiDoc attributes) go.
    if lines and lines[0].strip() == "---":
        j = 1
        while j < len(lines) and lines[j].strip() != "---":
            j += 1
        i = j + 1
    in_fence = in_delim = False
    literal_indent = None
    while i < len(lines):
        line = lines[i]
        i += 1
        stripped = line.strip()
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        if ext == ".adoc" and ADOC_DELIM.match(stripped):
            in_delim = not in_delim
            continue
        if in_delim:
            continue
        # A reStructuredText directive or literal block: its indented body goes.
        if literal_indent is not None:
            if not stripped or (len(line) - len(line.lstrip())) > literal_indent:
                continue
            literal_indent = None
        if ext == ".rst" and RST_DIRECTIVE.match(line):
            literal_indent = len(line) - len(line.lstrip())
            continue
        if not stripped:
            if para:
                out.append(" ".join(para))
                para = []
            continue
        if ext == ".rst" and RST_UNDERLINE.match(line):
            continue
        if stripped.startswith(("|", "+--", "#!", ":", "[", "include::", "image::", "ifdef::", "endif::", "//", "{{")):
            continue
        if line.startswith(("    ", "\t")) and ext == ".md":
            continue
        para.append(LIST_MARK.sub("", stripped.lstrip("#").strip()))
        if ext == ".rst" and stripped.endswith("::"):
            literal_indent = len(line) - len(line.lstrip())
            out.append(" ".join(para))
            para = []
    if para:
        out.append(" ".join(para))
    return out


def clean(text):
    text = SHORTCODE.sub(" ", text)
    text = MD_IMAGE.sub(" ", text)
    text = MD_LINK.sub(r"\1", text)
    text = MD_REF_LINK.sub(r"\1", text)
    text = RST_LINK.sub(r"\1", text)
    text = RST_ROLE.sub(r"\1", text)
    text = ADOC_LINK.sub(r"\1", text)
    text = ADOC_XREF.sub(r"\1", text)
    text = HTML_TAG.sub(" ", text)
    text = URL.sub(" ", text)
    text = INLINE_MARKS.sub("", text)
    return re.sub(r"\s+", " ", text).strip()


def sentences(text):
    for s in SENTENCE_END.split(clean(text)):
        s = s.strip()
        words = WORD.findall(s)
        # At least four words, and mostly words rather than symbols or numbers.
        if len(words) < 4 or sum(len(w) for w in words) < 0.6 * len(s.replace(" ", "")):
            continue
        yield s


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    out = sys.argv[1]
    total = 0
    with open(out, "w", encoding="utf-8") as fh:
        for spec in sys.argv[2:]:
            name, root = spec.split("=", 1)
            n = 0
            for dirpath, _, files in os.walk(root):
                for f in sorted(files):
                    if not f.endswith((".md", ".rst", ".adoc")):
                        continue
                    for p in paragraphs(os.path.join(dirpath, f)):
                        for s in sentences(p):
                            fh.write(f"{name}\t{s}\n")
                            n += 1
            print(f"{name}: {n} sentences")
            total += n
    print(f"wrote {total} sentences to {out}")


if __name__ == "__main__":
    main()
