# Adding dynamic wiki pages

Dynamic wiki pages are created from game data at generation time — one wiki file per entry in a JSON asset. They are registered in [`wiki/src/pages.py`](../src/pages.py) via a dedicated `add_*_pages()` function, called after `add_static_pages()`.

This does not cover fixed static pages (one page ID, one `gen_*` function). For those, see [Adding static wiki pages](ADDING_STATIC_WIKI_PAGES.md).

Run `python -m src validity` from the `wiki/` directory after making changes to confirm the directory, hierarchy, and content mappings are consistent.

## When to use dynamic pages

Use dynamic registration when:

- Each entry in a data file should have its own wiki page (e.g. one page per raid boss).
- New game content should appear automatically when the JSON asset is updated — no manual edits to a page list.

Use a static page with section templates instead when all entries should live on a single overview page (e.g. `gen_bosses()` listing every boss on `Bosses.md`). Bosses use both: a static overview page and individual dynamic pages.

## 1. Add an `add_*_pages()` function

Create a registration function in the **Page Listings** section of `pages.py`. The function loads a dict-shaped asset, builds one `PageInfo` per entry, registers them, and merges their page IDs into the hierarchy:

```python
def add_item_pages():
    data = load("some_asset.json")
    pages = {
        page_id: PageInfo(
            entry["display_name"],
            f"{page_id}.md",
            lambda entry=entry: gen_item(entry),
        )
        for page_id, entry in data.items()
    }
    PAGE_DIRECTORY.update(pages)
    PAGE_HIERARCHY.merge([["Parent Section", [["Child Section", list(pages)]]]])
```

Each `PageInfo` needs a page ID, title, output filename, and generator. The `lambda entry=entry` default argument binds each loop entry at definition time — without it, every page would share the last value from the loop.

See `add_boss_pages()` in `pages.py` for a real implementation.

## 2. Place pages in the hierarchy

Unlike static pages, dynamic pages are merged into `PAGE_HIERARCHY` directly — not via the `pages` list in `add_static_pages()`.

- A **page** is a plain page ID string (e.g. `"goblin_king"`).
- A **section** is a list `[section_name, children]`.

```python
PAGE_HIERARCHY.merge([
    ["Combat", [
        ["Bosses", ["goblin_king", "dragon_lord"]],
    ]],
])
```

`PageHierarchy.merge()` appends into an existing section when the name already exists (e.g. `"Combat"` from static pages), so you only need to specify the path to the new subsection.

Every dynamic page in `PAGE_DIRECTORY` must appear in the hierarchy merge. Pages with underscore-prefixed filenames are excluded from this requirement.

## 3. Implement a `gen_*` function (and template)

Dynamic pages use a generator that accepts the asset entry as an argument:

```python
def gen_boss(boss: dict) -> str:
    return get_template("combat/boss").format(
        name=boss["display_name"],
        # ...
    )
```

Add a single shared template under `wiki/templates/` (e.g. `combat/boss.md`) with `{placeholder}` fields filled from the asset dict.

Common helpers are the same as for static pages — see [Adding static wiki pages](ADDING_STATIC_WIKI_PAGES.md#3-implement-a-gen_-function-and-template).

If the overview static page links to individual dynamic pages, use `link(page_id)` where `page_id` matches the key used in `PAGE_DIRECTORY`.

## 4. Register at import time

Call the new function at the bottom of `pages.py`, after `add_static_pages()`:

```python
add_static_pages()
add_boss_pages()
```

## Checklist

1. Add a template under `wiki/templates/` for one item's layout.
2. Implement `gen_<item>(entry: dict) -> str` in the **Page Creation** section of `pages.py`.
3. Add `add_<items>_pages()` that loads the JSON asset, builds `PageInfo` entries, updates `PAGE_DIRECTORY`, and merges into `PAGE_HIERARCHY`.
4. Call `add_<items>_pages()` at the bottom of `pages.py`.
5. Run `python -m src validity` and fix any reported errors or warnings.

## Existing example

| Asset | Registration | Generator | Template | Hierarchy location |
|-------|--------------|-----------|----------|-------------------|
| `raid_bosses.json` | `add_boss_pages()` | `gen_boss(boss)` | `combat/boss.md` | Combat → Bosses |

The static overview page `bosses` (`gen_bosses()`, `combat/bosses.md`) lists all bosses on one page; dynamic pages provide a dedicated page per boss.
