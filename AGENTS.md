# Project Agent Guidelines

## graphify
This repository maintains a local knowledge graph in `graphify-out/` (`graphify-out/graph.json`, `graphify-out/GRAPH_REPORT.md`, `graphify-out/manifest.json`).

Rules:
- When `graphify-out/graph.json` exists, run `graphify query "<question>"` before your first grep, search, or source-file read for a codebase or architecture question, and pass this rule to subagents exploring code.
- If `graphify` is not on `PATH` (for example on Windows user-site Python installs), invoke it via `python -m graphify ...` or the interpreter recorded in `graphify-out/.graphify_python`.
- Use `graphify path "<A>" "<B>"` for cross-module relationships, `graphify explain "<concept>"` for focused nodes, and `graphify section "<heading>"` for Markdown sections. Read raw source files only after locating targets via the graph (or when debugging/editing specific lines).
- Dirty `graphify-out/` files are expected after git hooks or incremental updates; do not skip the graph unless the task is explicitly about stale or broken graph output.
- After modifying code files in a session, run `graphify update .` (AST-only, no LLM needed) so `graphify-out/` stays current.
- Read `graphify-out/GRAPH_REPORT.md` only for broad architecture overviews or when `query` / `path` / `explain` do not surface enough context.

## Core Engineering Standards
- Keep changes minimal and idiomatic; do not write unnecessary comments (`dont write unnececary comments`).
- Pair any JPA entity schema changes with explicit PostgreSQL Flyway migrations (`backend/src/main/resources/db/migration/`).
- Never hardcode secrets or fallback passwords in `docker-compose.yml` or `application.properties`; rely on `.env`.
- Keep Dockerfiles self-contained via multi-stage builds and maintain unified `/api/...` routes.
