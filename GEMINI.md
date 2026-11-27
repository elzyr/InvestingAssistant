## graphify
This project has a knowledge graph at `graphify-out/` with god nodes, community structure, and cross-file relationships.

Rules:
- When `graphify-out/graph.json` exists, run `graphify query "<question>"` (or `python -m graphify query "<question>"` via the interpreter in `graphify-out/.graphify_python`) before your first grep, search, or source-file read for a codebase question, and include this rule in exploration subagent prompts.
- Read raw source files only after the graph locates relevant files/lines. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts.
- Read `graphify-out/GRAPH_REPORT.md` only for broad architecture review or when `query`/`path`/`explain` do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current.
