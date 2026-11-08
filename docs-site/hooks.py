"""Build hooks for the docs site.

Pages under docs/ link to source files, workflows, module READMEs and agent
instructions with repo-relative paths, which read correctly on GitHub. The site
renders only docs/, minus what properdocs.yml excludes, so those targets are not
pages. This hook rewrites each such link to the file on GitHub, and only when the
target exists in the checkout: a link to nothing is left alone for `--strict`
to report. A link into a home directory (`~/...`) resolves on no host, so it
becomes its link text and the path in code.

It also tells overrides/main.html where a page's Markdown copy is, for the
"View as Markdown" and "Copy page as Markdown" buttons. The llmstxt plugin
writes that copy for each page its `sections` list.
"""

import fnmatch
import os
import re

import markdown.extensions.toc as toc
from mkdocs.structure.files import File, Files
from mkdocs.structure.pages import Page


def _github_unique(id: str, ids) -> str:
    """A repeated heading's anchor, as GitHub spells it: `x`, `x-1`, `x-2`."""
    base, n = id, 0
    while id in ids or not id:
        n += 1
        id = f"{base}-{n}"
    ids.add(id)
    return id


# Python-Markdown suffixes `_1`; pages written against GitHub link to `-1`.
toc.unique = _github_unique

# An inline link or image target, `](target)` or `](target "title")`.
_INLINE = re.compile(r'(\]\()(<[^>]*>|[^)\s]+)((?:\s+"[^"]*")?\))')
# A reference definition, `[label]: target`.
_REFERENCE = re.compile(r'^(\s{0,3}\[([^\]]+)\]:\s+)(\S+)(.*)$')
# An inline link into a home directory, `[text](~/path)`.
_HOME_INLINE = re.compile(r'(?<!!)\[([^\]]*)\]\((~/[^)\s]*)\)')
_FENCE = re.compile(r'^\s*(```|~~~)')
_SCHEME = re.compile(r'^[a-zA-Z][a-zA-Z0-9+.-]*:')


def _github_url(config, repo_path: str, is_dir: bool) -> str:
    kind = "tree" if is_dir else "blob"
    return f"{config['repo_url'].rstrip('/')}/{kind}/main/{repo_path}"


def _rewrite(target: str, page: Page, config, files: Files, repo_root: str) -> str:
    bare = target[1:-1] if target.startswith("<") else target
    if not bare or bare.startswith(("#", "/", "~")) or _SCHEME.match(bare):
        return target
    path, _, anchor = bare.partition("#")
    if not path:
        return target
    page_dir = os.path.dirname(page.file.abs_src_path)
    abs_target = os.path.normpath(os.path.join(page_dir, path))

    site_file = _site_files.get(abs_target)
    if site_file is not None:
        # Relative between the two pages' places on the site, which differ from
        # their places in the repo for a page from outside docs/.
        from_dir = os.path.dirname(page.file.src_uri)
        rel = os.path.relpath(site_file.src_uri, from_dir or ".").replace(os.sep, "/")
        return rel + (f"#{anchor}" if anchor else "")
    if not abs_target.startswith(repo_root + os.sep) or not os.path.exists(abs_target):
        return target

    repo_path = os.path.relpath(abs_target, repo_root).replace(os.sep, "/")
    url = _github_url(config, repo_path, os.path.isdir(abs_target))
    return url + (f"#{anchor}" if anchor else "")


# Every file the site renders, by its path in the checkout.
_site_files: dict = {}


def on_files(files: Files, config, **kwargs) -> Files:
    """Add the pages that live outside docs/, then index every rendered file."""
    repo_root = os.path.normpath(os.path.join(config["docs_dir"], os.pardir))
    for src_uri, repo_path in config["extra"].get("outside_pages", {}).items():
        page_file = File.generated(config, src_uri, abs_src_path=os.path.join(repo_root, repo_path))
        page_file.edit_uri = f"../{repo_path}"
        files.append(page_file)
    _site_files.clear()
    for f in files:
        if f.abs_src_path and not f.inclusion.is_excluded():
            _site_files[os.path.normpath(f.abs_src_path)] = f
    return files


def _unlinked(text: str, path: str) -> str:
    if text.strip("`") == path:
        return f"`{path}`"
    return f"{text} (`{path}`)"


def _prose_lines(lines: list[str]):
    """Yield the index of every line outside a fenced code block."""
    in_fence = False
    for i, line in enumerate(lines):
        if _FENCE.match(line):
            in_fence = not in_fence
        elif not in_fence:
            yield i


def on_page_markdown(markdown: str, page: Page, config, files: Files, **kwargs) -> str:
    repo_root = os.path.normpath(os.path.join(config["docs_dir"], os.pardir))
    lines = markdown.split("\n")
    prose = list(_prose_lines(lines))

    # Reference definitions into a home directory: drop them, unlink their uses.
    home_refs = {}
    for i in prose:
        m = _REFERENCE.match(lines[i])
        if m and m.group(3).startswith("~/"):
            home_refs[m.group(2).lower()] = m.group(3)
            lines[i] = None
    if home_refs:
        use = re.compile(r'(?<!!)\[([^\]]*)\]\[([^\]]*)\]')

        def unlink_use(m: re.Match) -> str:
            label = (m.group(2) or m.group(1)).lower()
            return _unlinked(m.group(1), home_refs[label]) if label in home_refs else m.group(0)

    for i in prose:
        line = lines[i]
        if line is None:
            continue
        if home_refs:
            line = use.sub(unlink_use, line)
        line = _HOME_INLINE.sub(lambda m: _unlinked(m.group(1), m.group(2)), line)
        line = _INLINE.sub(
            lambda m: m.group(1) + _rewrite(m.group(2), page, config, files, repo_root) + m.group(3),
            line,
        )
        line = _REFERENCE.sub(
            lambda m: m.group(1) + _rewrite(m.group(3), page, config, files, repo_root) + m.group(4),
            line,
        )
        lines[i] = line
    return "\n".join(line for line in lines if line is not None)


def _has_markdown_copy(src_uri: str, config) -> bool:
    """Whether the llmstxt plugin writes a Markdown copy of this page."""
    plugin = config["plugins"].get("llmstxt")
    if plugin is None:
        return False
    for inputs in plugin.config.sections.values():
        for item in inputs:
            pattern = next(iter(item)) if isinstance(item, dict) else item
            if fnmatch.fnmatchcase(src_uri, pattern):
                return True
    return False


def on_page_context(context, page: Page, config, nav, **kwargs):
    """Point main.html at the page's Markdown copy: its dest_uri, `.html` → `.md`."""
    if _has_markdown_copy(page.file.src_uri, config):
        page.meta["markdown_url"] = os.path.splitext(page.file.dest_uri)[0] + ".md"
    return context
