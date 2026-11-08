# The plan's data format

The Project pages render from one file, [plan.yaml](plan.yaml), checked against
[plan.schema.json](plan.schema.json) (JSON Schema 2020-12). Both are included below from the
repo at build time, so this page can't drift from them.

## Shape

The format is chosen so a bee board can be seeded from it later:

- `items` is an object keyed by stable id. Each item is replaced whole on write. An id starts
  with `task-` (a v1 plan task), `bee-` (a bee issue or PR), `kuilt-` (kuilt work) or `out-`
  (work outside the code), and is never reused.
- `order` lists every id exactly once, in display order. On a board it is an Rga of ids.
- `columns` has one entry per status, and `column_order` puts them in order. On a board these are
  a `Board` notion's columns. A column with an `owner` is shown at the top of the
  [overview](index.md) as waiting on that owner.
- Timestamps are quoted UTC strings, `"2026-11-07T01:31Z"`. The pages show them in
  America/New_York.
- An `estimate` is a range, `low` to `high` in `hours` or `days`, with the `basis` it rests on and
  an optional `confidence`. The pages always label it as an estimate and never draw it on a dated
  axis.
- A relative link URL is relative to `docs/project/`, so it works on GitHub and on the site.

The YAML holds plain JSON values only: no anchors, no tags, no unquoted dates.

## Checks

The build fails when the file breaks the schema, or when it names an id that isn't an item:
an `order` entry, a `depends_on` entry or a `parent`. It also fails when an item is missing from
`order`, or has a status with no column. The check lives in
[`docs-site/plan_pages.py`](../../docs-site/plan_pages.py).

## The schema

```json
--8<-- "docs/project/plan.schema.json"
```

## The data

??? example "plan.yaml, in full"

    ```yaml
    --8<-- "docs/project/plan.yaml"
    ```
