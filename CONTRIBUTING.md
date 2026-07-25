# Contributing to WLT-AdBlocker

Thank you for your interest in contributing to WLT-AdBlocker! This document outlines the process for contributing to the project.

## Code of Conduct

Be respectful, constructive, and helpful. We're all here to make a better ad blocker.

## How to Contribute

### Reporting Bugs

1. Check existing issues to avoid duplicates
2. Open a new issue with:
   - **Title**: Clear, descriptive summary
   - **Device**: Phone model + Android version
   - **WLT version**: Check Settings → About
   - **Steps to reproduce**: Detailed steps
   - **Expected behavior**: What should happen
   - **Actual behavior**: What actually happens
   - **Logs**: If possible, export the query log (Settings → Export → CSV)

### Suggesting Features

1. Check the [ROADMAP.md](ROADMAP.md) — your idea might already be planned
2. Open an issue with the `enhancement` label
3. Describe the use case, not just the solution

### Pull Requests

1. **Fork** the repository
2. **Create a branch**: `git checkout -b feature/my-feature`
3. **Write code** following the style below
4. **Test**: `cd wlt-core && go test ./... -count=1`
5. **Lint**: `cd wlt-core && go vet ./...`
6. **Commit**: Use clear commit messages (see below)
7. **Push** and open a pull request

### Commit Message Format

```
type(scope): short description

Longer description if needed.
```

Types:
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation
- `refactor`: Code restructuring
- `test`: Test additions
- `chore`: Maintenance

Scopes:
- `engine`: Go block engine
- `vpn`: Android VPN service
- `ui`: Compose screens
- `scriptlets`: Scriptlet engine
- `blocklists`: Blocklist files
- `docs`: Documentation

Examples:
```
feat(engine): add regex matching to block cascade
fix(vpn): close DatagramSocket in finally block
docs(readme): update scriptlet count to 80
feat(scriptlets): add de-amp scriptlet (Brave technique)
```

## Code Style

### Go

- Follow [Effective Go](https://go.dev/doc/effective_go)
- Use `gofmt` / `goimports`
- Every public function needs a doc comment
- Tests are required for new packages
- All trie/map operations must be thread-safe (RWMutex)

### Kotlin

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use `val` over `var` where possible
- Prefer Compose over XML layouts
- All VPN writes must be in `synchronized(outputLock) { }` blocks
- All sockets must be closed in `finally` blocks
- Use `lifecycleScope.launch` (NOT `MainScope().launch`) in activities
- Use `Icons.AutoMirrored.Filled.List` (NOT `Icons.Filled.List`)

## Project Structure

- `wlt-core/` — Go core engine (compiled to .aar via gomobile)
- `android/` — Android app (Kotlin + Jetpack Compose)
- `research-2025/` — Deep research documentation
- `scripts/` — Build/deploy scripts

## Testing

### Go Tests
```bash
cd wlt-core
go test ./... -count=1 -v
go vet ./...
```

### Android Tests
```bash
cd android
./gradlew test
./gradlew connectedAndroidTest
```

## Building

See [README.md](README#building-from-source) for build instructions.

## License

By contributing, you agree that your contributions will be licensed under the GPL v3 license.
