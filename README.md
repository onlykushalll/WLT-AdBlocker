# WLT-Adblocker

Production-grade, privacy-first, system-wide ad blocker — built from the best ideas in open-source adblocking.

## Status

**Phase 0: Research & Architecture** (current)

See the research docs before implementation:

| Document | Description |
|----------|-------------|
| [docs/RESEARCH.md](docs/RESEARCH.md) | Deep research: how adblocking works, limitations, bypass techniques |
| [docs/MASTER-FEATURE-INVENTORY.md](docs/MASTER-FEATURE-INVENTORY.md) | **300+ features** catalogued across 35+ adblockers |
| [docs/WLT-UNIQUE-FEATURES.md](docs/WLT-UNIQUE-FEATURES.md) | Features WLT will have that **no other blocker combines** |
| [docs/ADBLOCKER-COMPARISON.md](docs/ADBLOCKER-COMPARISON.md) | Side-by-side comparison of top open-source blockers |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | WLT-Adblocker proposed architecture & 5-phase build plan |

## Why WLT-Adblocker?

Existing adblockers fail in predictable ways:

- **DNS-only blockers** (Blokada, AdGuard DNS) miss YouTube in-app ads and first-party ads
- **Browser extensions** don't run inside games or native apps
- **Paid tiers** gate basic features behind subscriptions
- **Trust issues** with closed-source blockers that see all your traffic

WLT-Adblocker combines proven open-source techniques into a **multi-layer** system:

1. **DNS layer** — blocks 80–90% of ads/trackers system-wide (games, apps, browsers)
2. **HTTPS proxy layer** — URL-level filtering + cosmetic/scriptlet injection for browsers
3. **Smart bypass handling** — CNAME uncloaking, SNI inspection, hardcoded-IP fallbacks
4. **100% on-device** — no cloud dependency, no telemetry, fully auditable

## Target Platform

**Android first** (primary pain point: phone, games, YouTube app).

Desktop/browser extension is Phase 2.

## License

GPL-3.0 (compatible with AdGuard urlfilter, uBlock filter lists)
