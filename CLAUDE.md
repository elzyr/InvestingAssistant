@AGENTS.md

## graphify
This project has a knowledge graph at `graphify-out/` with god nodes, community structure, and cross-file relationships.

Rules:
- When `graphify-out/graph.json` exists, run `graphify query "<question>"` (or `python -m graphify query "<question>"` via `graphify-out/.graphify_python`) before your first grep, search, or source-file read for a codebase question, and put this rule in subagent exploration prompts.
- Read raw files only for lines the graph or an earlier read already located. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts.
- Read `graphify-out/GRAPH_REPORT.md` only for broad architecture review or when `query`/`path`/`explain` do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current.
