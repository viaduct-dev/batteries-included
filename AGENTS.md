# Repository Guidelines

## Viaduct Documentation

When looking for Viaduct documentation, check these sources in order:

1. **https://viaduct.airbnb.tech/sitemap.xml** — official Viaduct documentation site. Use the sitemap to discover valid pages. Do NOT guess or fabricate paths under this domain.
2. **https://github.com/airbnb/viaduct** — source code and README
3. The `.graphqls` schema files and resolver classes in this project's `backend/src/main/` directory
4. The example files under `backend/src/main/kotlin/com/example/examples/`

## Project Structure & Module Organization
- `src/` houses the React + Vite UI (components, pages, shared `lib/`, hooks) with global styles in `index.css`.
- `backend/` runs the Viaduct GraphQL service: Kotlin lives in `src/main/kotlin`, module schemas in each `viaduct/schema`, Gradle build files at the root.
- Local Supabase manifests and migrations sit in `supabase/`.
- Playwright specs land in `e2e/` with artifacts under `playwright-report/` and `test-results/`.

## Build, Test, and Development Commands
- `mise install` provisions Java, Podman, Supabase CLI, and exports dev env vars.
- `mise run dev` starts Supabase, backend, and frontend; `mise run stop`/`mise run status` manage lifecycle.
- `npm run dev` runs only the UI; `npm run build` + `npm run preview` check production output.
- `npm run lint` enforces the ESLint config—resolve warnings before merging.
- `npm run test:e2e` (plus `:ui`, `:headed`, `:debug`) drives Playwright suites after UI-impacting work.
- `cd backend && ./gradlew build` compiles the Kotlin service; `./gradlew test` runs backend unit coverage.

## Coding Style & Naming Conventions
- TypeScript stays in functional React components with 2-space indentation and strict typing. Components use `PascalCase`, hooks `useThing`, and helpers `camelCase`.
- Keep Tailwind utility strings near the elements they style; order classes layout → spacing → color and prefer `clsx`/`tailwind-merge` helpers.
- ESLint (`eslint.config.js`) enforces React Hooks and TypeScript rules—run `npm run lint` or enable on-save fixes in your editor.

## Testing Guidelines
- Add or update Playwright specs in `e2e/*.spec.ts` for meaningful UI flows; assert visible outcomes rather than implementation details.
- Place backend coverage in `backend/src/test/kotlin`; favor deterministic tests and stub Supabase access when live data is unnecessary.

## Environment & Security Notes
- Credentials in `mise.toml` support local work only; override via `.env.local` for personal secrets and avoid committing new keys.
- Default GraphQL endpoint is `http://localhost:8080/graphql`; update clients in `src/lib` when targeting staging or production.
