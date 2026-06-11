# Adding static wiki pages

Static wiki pages are fixed pages with a single `gen_*` function — one page ID, one output file. They are registered in [`wiki/src/pages.py`](../src/pages.py) inside `add_static_pages()`.

This does not cover data-driven pages that create one wiki file per game asset (e.g. individual boss pages from `raid_bosses.json`). For those, see [Adding dynamic wiki pages](ADDING_DYNAMIC_WIKI_PAGES.md).

Run `python -m src validity` from the `wiki/` directory after making changes to confirm the directory, hierarchy, and content mappings are consistent.

## 1. Add the page to the `pages` list

Static pages are declared in the `pages` list inside `add_static_pages()`. This list registers each page in `PAGE_DIRECTORY` and places it in `PAGE_HIERARCHY` in one step.

Each entry is a `PageInfo` with the display title, output filename, and content generator:

```python
("agility", PageInfo("Agility", "Agility.md", gen_agility))
```

- **Page ID** — the dict key (lowercase, no spaces), used when referring to the page elsewhere (e.g. `link("agility")`).
- **Title** — shown in wiki links (e.g. `[[Agility|Agility]]`).
- **URL / filename** — the markdown file written to the wiki repo (e.g. `Agility.md`).
- **Generate** — the `gen_<page_id>()` function that returns the page content.

`PAGE_DIRECTORY` and `PAGE_HIERARCHY` are populated from this list automatically at import time — you do not need to update them separately.

## 2. Place the page in the hierarchy

The same `pages` list defines where the page appears in navigation (home page and `_Sidebar.md`). `_gen_page_listing()` walks `PAGE_HIERARCHY` to build markdown links and headings.

- A **page** is a tuple `(page_id, PageInfo(...))`.
- A **section** is a list `[section_name, children]`, where `section_name` is a display-only heading and `children` is a list of further entries. Sections can be nested to any depth.

```python
pages = [
    ("home", PageInfo("Home", "Home.md", gen_home)),
    ["Skills", [
        ("skills", PageInfo("Skills", "Skills.md", gen_skills)),
        ["Gathering", [
            ("mining", PageInfo("Mining", "Mining.md", gen_mining)),
            ("fishing", PageInfo("Fishing", "Fishing.md", gen_fishing)),
        ]],
    ]],
    ["Combat", [
        ("bosses", PageInfo("Bosses", "Bosses.md", gen_bosses)),
        ("dungeons", PageInfo("Dungeons", "Dungeons.md", gen_dungeons)),
    ]],
]
```

Section names are display-only headings — link text for pages comes from `PageInfo.title`. The same section name can appear in more than one place (e.g. Combat under Skills and as a top-level section); existing sections are merged when the name already exists.

Every static page should appear at least once in the `pages` list. Pages with underscore-prefixed filenames (e.g. `_Sidebar.md`) are excluded from this requirement.

### Pages outside the hierarchy

Special static pages such as the sidebar should not appear in navigation. Register them separately, before the `pages` list:

```python
PAGE_DIRECTORY.update({
    "sidebar": PageInfo("Sidebar", "_Sidebar.md", gen_sidebar),
})
```

## 3. Implement a `gen_*` function (and template)

Each static page has a `gen_<page_id>()` function in the **Page Creation** section of `pages.py`. These functions load game data from `app/src/main/assets/data/` and return formatted markdown.

Common helpers:

| Helper                              | Purpose                                |
|-------------------------------------|----------------------------------------|
| `load("some_file.json")`            | Load a JSON asset                      |
| `get_template("skills/gathering/agility")` | Load a template from `wiki/templates/` |
| `table(headers, rows)`              | Build a markdown table                 |
| `link("agility")`                   | Build a wiki link to another page      |
| `title("iron_ore")`                 | Format an asset key as a title         |

Example pattern (see `gen_agility()`):

1. Add a template file, e.g. `wiki/templates/skills/gathering/agility.md`, with `{placeholder}` fields.
2. Implement `gen_agility()` to load data, build tables/rows, and call `get_template("skills/gathering/agility").format(...)`.
3. Add `("agility", PageInfo("Agility", "Agility.md", gen_agility))` to the `pages` list under the appropriate section.

`get_pages()` calls each entry's `generate` function to produce the final `{filename: content}` mapping written to disk.

## Checklist

1. Add `("page_id", PageInfo("Title", "Title.md", gen_page_id))` to the `pages` list in `add_static_pages()` (nested under the appropriate section).
2. Implement `gen_<page_id>()` and any template file under `wiki/templates/`.
3. Run `python -m src validity` and fix any reported errors or warnings.

## Section templates for repeated content

Some static pages are made up of many similar blocks on a single file (one section per dungeon, shop category, etc.). Split the work across two templates:

1. **Page template** — static intro text and a single placeholder for all sections (e.g. `{boss_sections}` in `combat/bosses.md`).
2. **Section template** — the layout for one item, with placeholders filled per entry (e.g. `combat/boss_section.md`).

Put reusable parsing logic (loot rows, spawn tables, reward strings) in helper functions alongside the other helpers in `pages.py`, and keep templates focused on markdown structure.

**When to use a section template**

- The same markdown layout is repeated many times on one page.
- A section has several placeholders or non-trivial formatting.

**When to keep it inline**

- Sections are very simple — e.g. a heading plus a table (see `gen_quests()`).

Existing examples:

| Page     | Page template              | Section template              |
|----------|----------------------------|-------------------------------|
| Bosses   | `combat/bosses.md`         | `combat/boss_section.md`      |
| Dungeons | `combat/dungeons.md`       | `combat/dungeon_section.md`   |
| Enemies  | `combat/enemies.md`        | `combat/enemy_section.md`     |
| Shop     | `town/shop.md`             | `town/shop_section.md`        |

Templates should be organised into subfolders that match the wiki hierarchy (e.g. `skills/gathering/`, `combat/`, `town/`, `miscellaneous/`).
