#!/usr/bin/env python3
"""Genera docs/privacy/index.html dalle informative in Markdown (IT e EN) — Spec §10.
Aggiunto il 22/10/2026 alle 11:51. Requisito: pacchetto Python "markdown" (pip install markdown).

Uso: python3 tools/docs/build-privacy-html.py
La pagina è autonoma (nessuna risorsa esterna, nessun cookie, nessuno script di terzi) e va pubblicata
sul sito del titolare; il suo indirizzo va inserito nelle schede degli store e nell'app.
"""
import re
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import markdown

ROOT = Path(__file__).resolve().parents[2]
SRC = {"it": ROOT / "docs/privacy/informativa-privacy.it.md", "en": ROOT / "docs/privacy/privacy-policy.en.md"}
OUT = ROOT / "docs/privacy/index.html"
INTERNAL = re.compile(r"^\*(Aggiunto|Testo destinato|Added on|Text intended)[^\n]*\*\s*$", re.M)


def render(path: Path) -> str:
    text = INTERNAL.sub("", path.read_text(encoding="utf-8"))
    html = markdown.markdown(text, extensions=["tables"])
    html = re.sub(r"(https?://[^\s<)]+)", r'<a href="\1">\1</a>', html)
    html = html.replace("[[", '<mark>[[').replace("]]", "]]</mark>")
    return html


TEMPLATE = """<!doctype html>
<!-- Generato da tools/docs/build-privacy-html.py il {when} (ora italiana). Spec §10. Non modificare a mano. -->
<html lang="it">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Zzitto – Privacy</title>
<style>
:root {{ --bg:#fbfaf7; --fg:#1d1d1b; --muted:#5b5a55; --line:#dedbd2; --accent:#2f5d50; --mark:#fff1a8; }}
@media (prefers-color-scheme: dark) {{ :root {{ --bg:#161614; --fg:#ecebe6; --muted:#a9a79f; --line:#34332f; --accent:#8cc7b3; --mark:#5c5220; }} }}
* {{ box-sizing:border-box; }}
body {{ margin:0; background:var(--bg); color:var(--fg); font:16px/1.6 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif; }}
main {{ max-width:860px; margin:0 auto; padding:24px 16px 64px; }}
nav {{ display:flex; gap:12px; border-bottom:1px solid var(--line); padding-bottom:12px; margin-bottom:8px; }}
nav a {{ color:var(--accent); font-weight:600; text-decoration:none; }}
h1 {{ font-size:1.7rem; line-height:1.25; }}
h2 {{ font-size:1.2rem; margin-top:2rem; border-top:1px solid var(--line); padding-top:1rem; }}
a {{ color:var(--accent); overflow-wrap:anywhere; }}
.table {{ overflow-x:auto; }}
table {{ border-collapse:collapse; width:100%; font-size:.95rem; }}
th, td {{ border:1px solid var(--line); padding:6px 8px; text-align:left; vertical-align:top; }}
th {{ color:var(--muted); }}
mark {{ background:var(--mark); color:inherit; }}
section + section {{ margin-top:48px; border-top:3px double var(--line); }}
</style>
</head>
<body>
<main>
<nav><a href="#it" hreflang="it">Italiano</a><a href="#en" hreflang="en">English</a></nav>
<section id="it" lang="it">
{it}
</section>
<section id="en" lang="en">
{en}
</section>
</main>
</body>
</html>
"""


def main() -> None:
    when = datetime.now(ZoneInfo("Europe/Rome")).strftime("%d/%m/%Y %H:%M")
    parts = {k: render(v) for k, v in SRC.items()}
    for k in parts:
        parts[k] = parts[k].replace("<table>", '<div class="table"><table>').replace("</table>", "</table></div>")
    OUT.write_text(TEMPLATE.format(when=when, **parts), encoding="utf-8")
    print(f"scritto {OUT}")


if __name__ == "__main__":
    main()
