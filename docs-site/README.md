# The docs site

The pages under `docs/`, built into a site with [ProperDocs](https://properdocs.org) (a
continuation of MkDocs 1.6) and the [Material](https://squidfunk.github.io/mkdocs-material/)
theme. It's published to GitHub Pages at <https://tractat-us.github.io/bee/>.

## Preview it

From the repo root, in a venv of its own:

```bash
python3 -m venv docs-site/.venv
docs-site/.venv/bin/pip install -r docs-site/requirements.txt
DISABLE_MKDOCS_2_WARNING=true docs-site/.venv/bin/properdocs serve -f docs-site/properdocs.yml
```

Then open <http://127.0.0.1:8000>. It rebuilds as you save. Run `properdocs` only, never
`mkdocs`: ProperDocs loads the MkDocs plugins under its own name.

Before you push, run the check CI runs. It fails on a broken link or anchor:

```bash
DISABLE_MKDOCS_2_WARNING=true docs-site/.venv/bin/properdocs build --strict -f docs-site/properdocs.yml
```

Run both from the repo root. An include (`--8<-- "path"`) names its file from there, and a build
started anywhere else can't find it.

## Add a page

1. Write it under `docs/`.
2. Add it to `nav:` in [`properdocs.yml`](properdocs.yml). Strict mode fails on a page missing
   from the nav.
3. Add it to a section under the `llmstxt` plugin in the same file, so it gets a Markdown copy
   and a line in `llms.txt`.
4. Link to other pages and to source files with ordinary relative paths, the same as on GitHub. A
   link to a file the site does not render (Kotlin source, a workflow, an `AGENTS.md`) becomes a
   link to that file on GitHub at build time.

Diagrams are Mermaid, in a fenced block marked `mermaid`. A code block can carry
[annotations](https://squidfunk.github.io/mkdocs-material/reference/code-blocks/#adding-annotations):
a `# (1)!` comment, then a numbered list straight after the block.

A page that lives outside `docs/`, such as the root `README.md`, is listed under
`extra.outside_pages` in `properdocs.yml`. Its path must also be in the `paths:` filters in
[`.github/workflows/docs-site.yml`](../.github/workflows/docs-site.yml), so that editing it
rebuilds the site.

## What is not rendered

- Agent instructions, the `AGENTS.md` and `CLAUDE.md` files. Pages link to them on GitHub.
- Session scratch, `next-plan*.md` and `morning-report*.md`.

## For agents

Every page in an `llmstxt` section has a Markdown copy beside its HTML: `setup/` is also
`setup/index.md`. The site root serves `llms.txt`, an index of those copies, and `llms-full.txt`,
all of them in one file. Each page has a "View as Markdown" link and a "Copy page as Markdown"
button by its title.

## How it builds

[`hooks.py`](hooks.py) does four things at build time:

- It rewrites links to files outside the site as GitHub links.
- It adds the pages that live outside `docs/`.
- It numbers repeated headings' anchors the way GitHub does, so a link written for GitHub lands on
  the site.
- It tells [`overrides/main.html`](overrides/main.html) where each page's Markdown copy is.

[`plan_pages.py`](plan_pages.py) renders the Project tab. Each page under `docs/project/` is a
stub with a marker such as `<!-- plan:timeline -->`, which the hook replaces with Markdown written
from [`docs/project/plan.yaml`](../docs/project/plan.yaml). It checks the file against
[`plan.schema.json`](../docs/project/plan.schema.json) first, then runs the checks the schema
can't express, and fails the build on any of them. To change what the Project pages say, edit
`plan.yaml`; the [data format page](../docs/project/schema.md) describes it and lists the checks.

[`llmstxt_preprocess.py`](llmstxt_preprocess.py) fixes the HTML before the llmstxt plugin turns it
back into Markdown: Mermaid blocks keep their language, and annotation markers lose their `!`.

[`seo.py`](seo.py) gives each page a search-result description from its first paragraph. A page
whose opening makes a poor summary sets its own with `description:` front matter.
`overrides/main.html` turns that description, the title and a card image into the OpenGraph and
Twitter tags a link preview reads. The card is
[`overrides/assets/images/social-card.png`](overrides/assets/images/social-card.png), drawn from
[`social-card.svg`](social-card.svg). After editing the SVG, render it again from the repo root:

```bash
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --hide-scrollbars \
  --window-size=1200,630 --screenshot="$PWD/docs-site/overrides/assets/images/social-card.png" \
  "file://$PWD/docs-site/social-card.svg"
```

The sitemap, each page's canonical link, and the URLs in `llms.txt` all come from `site_url` in
`properdocs.yml`, so that line has to name the address the site is served from.

CI runs [`.github/workflows/docs-site.yml`](../.github/workflows/docs-site.yml) on a standard
runner. On a PR it runs the strict build as a check. On a push to `main` it builds and deploys.
It is not a required check, and `ci.yml` counts `docs-site/` as docs, so a change here never
starts the Gradle build.
