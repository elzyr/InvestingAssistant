## graphify

For any question about this repo's architecture, structure, components, or how to add/modify/find code, your first action should be `graphify query "<question>"` (or `python -m graphify query "<question>"` via `graphify-out/.graphify_python`) when `graphify-out/graph.json` exists. Use `graphify path "<A>" "<B>"` for relationship questions and `graphify explain "<concept>"` for focused-concept questions.

Read `graphify-out/GRAPH_REPORT.md` only for broad architecture review or when `query`/`path`/`explain` do not surface enough context. Only read source files directly when modifying/debugging specific code, when the graph lacks needed detail, or when the graph is missing or stale.

After code edits, run `graphify update .` to refresh the graph.
