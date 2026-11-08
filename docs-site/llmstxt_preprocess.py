"""Fix-ups the llmstxt plugin runs on each page's HTML before it turns it into Markdown.

The plugin reads the rendered page, so two things come back wrong without help:

- A Mermaid diagram is a `<pre class="mermaid">`, which carries no `language-`
  class, so its fence loses `mermaid`. This adds the class back.
- A code annotation marker, `# (1)!`, is literal text in the HTML. Material's
  script hides it in the browser; in Markdown the `!` means nothing, so this
  drops it and leaves `# (1)`, which still points at the list below the block.
"""

import re

from bs4 import BeautifulSoup, NavigableString

_ANNOTATION = re.compile(r"\((\d+)\)!")


def preprocess(soup: BeautifulSoup, output: str) -> None:
    for pre in soup.find_all("pre", class_="mermaid"):
        pre["class"] = [*pre.get("class", []), "language-mermaid"]

    for code in soup.find_all("code"):
        for text in code.find_all(string=_ANNOTATION):
            text.replace_with(NavigableString(_ANNOTATION.sub(r"(\1)", text)))
