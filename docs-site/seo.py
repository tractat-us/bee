"""Gives every page its own search-result description.

Material writes `<meta name="description">` from a page's `description` front
matter, and falls back to `site_description` for every page that has none. One
sentence repeated across the whole site reads to a search engine as duplicate
content, and a link shared in chat shows the same blurb whichever page it names.

So a page with no `description` gets its first paragraph of prose, trimmed to the
length a search result shows. Front matter still wins, which is how a page whose
opening paragraph makes a poor summary says something better. The homepage keeps
`site_description`: its opening paragraph is the quilting-bee picture, not a
summary. overrides/main.html reads the same value for the OpenGraph and Twitter
tags.
"""

import html as entities
import re

from bs4 import BeautifulSoup

# Search results show roughly 155 characters before cutting a description off.
_LIMIT = 155
# A paragraph shorter than this is a label or a status line, not a summary.
_SHORTEST = 40


def _summary(html: str) -> str | None:
    soup = BeautifulSoup(html, "html.parser")
    for p in soup.find_all("p"):
        # Paragraphs inside an admonition, a table or a list are asides, not the lede.
        if p.find_parent(["div", "table", "li", "blockquote", "details"]):
            continue
        text = re.sub(r"\s+", " ", p.get_text()).strip()
        if len(text) >= _SHORTEST:
            return _trim(text)
    return None


def _trim(text: str) -> str:
    if len(text) <= _LIMIT:
        return text
    cut = text[: _LIMIT - 1].rsplit(" ", 1)[0].rstrip(",;:—–-")
    return cut + "…"


def _is_home(page) -> bool:
    # page.is_homepage is False here: hooks.py adds index.md from README.md, outside docs_dir.
    return page.file.src_uri == "index.md"


def on_page_content(html, page, config, files):
    if _is_home(page):
        page.meta["home"] = True
        return html
    if page.meta.get("description"):
        return html
    summary = _summary(html)
    if summary:
        # Escaped here, because Material writes the value into an attribute as it is.
        page.meta["description"] = entities.escape(summary, quote=True)
    return html
