# Fork-only customizations (tuannx/embabel-agent)

These assets are maintained on the private fork only. Do **not** open pull requests
against `embabel/embabel-agent` for them.

| Path | Purpose |
|------|---------|
| `.github/workflows/arcade-agent-analysis.yml` | CI architecture analysis via arcade-agent 0.2.0 |
| `.cursor/rules/arcade-architecture.mdc` | Cursor rule for MCP architecture checks |
| `scripts/sync-upstream.sh` | Upstream sync (restores fork-only paths after merge) |
| `.gitignore` (arcade-agent section) | Local analysis output exclusions |

`scripts/sync-upstream.sh` re-applies fork-only paths after each upstream merge.
