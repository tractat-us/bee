# The plan's data format

The Project pages render from one file, [plan.yaml](plan.yaml), checked against
[plan.schema.json](plan.schema.json) (JSON Schema 2020-12). Both are included below from the
repo at build time, so this page can't drift from them.

## Shape

The format is chosen so a bee board can be seeded from it later:

- `items` is an object keyed by id. An id is opaque and stable, and is never reused. It starts
  with `task-` (a v1 plan task), `bee-` (a bee issue or PR), `kuilt-` (kuilt work) or `out-`
  (work outside the code). A slice keeps its draft id, such as `kuilt-3126-d`, once it is filed;
  its GitHub number lives only in `links`.
- Each item is replaced whole on write. On a board, that means a concurrent status move and
  summary edit on the same item keep only one of the two.
- `order` lists every id exactly once, in display order. On a board it is an Rga of ids.
- `columns` has one entry per status, and `column_order` puts them in order. On a board these are
  a `Board` notion's columns. A column with an `owner` is shown at the top of the
  [overview](index.md) as waiting on that owner.
- `parent` names the item this one is part of. A parent depends on its children by definition,
  so a child is never also in its parent's `depends_on`.
- `started` is when the item's PR opened, or when its issue was filed if it has no PR. `landed`
  is when it merged or closed, or for a decision, when it was ruled. Only a done item has one.
- Timestamps are quoted UTC strings, `"2026-11-07T01:31Z"`. The pages show them in
  America/New_York.
- An `estimate` is a range, `low` to `high` in `hours` or `days`, with the `basis` it rests on and
  an optional `confidence`. The pages always label it as an estimate and never draw it on a dated
  axis.
- A relative link URL is relative to `docs/project/`, so it works on GitHub and on the site.

The YAML holds plain JSON values only: no anchors, no tags, no unquoted dates. Every item allows
only the fields the schema lists, so an export from a board must bump `schema_version` before it
adds one, and must drop duplicate and missing ids from `order`.

## Checks

The build fails when the file breaks the schema. Beyond the schema, it fails when:

- a mapping repeats a key;
- an `order`, `depends_on` or `parent` entry names an id that isn't an item, or an item is
  missing from `order`;
- `column_order` and `columns` name different statuses, or an item's status has no column;
- `depends_on` or `parent` has a cycle, or a parent's `depends_on` lists its own child;
- a timestamp or date isn't a real one, an item lands before it starts, or an item that isn't
  done has `landed`;
- an estimate's `low` is above its `high`;
- a title, summary or basis spans more than one line.

The checks live in [`docs-site/plan_pages.py`](../../docs-site/plan_pages.py). Every link on an
item is rendered, so strict mode checks each one.

## The schema

```json
--8<-- "docs/project/plan.schema.json"
```

## The data

??? example "plan.yaml, in full"

    ```yaml
    --8<-- "docs/project/plan.yaml"
    ```
