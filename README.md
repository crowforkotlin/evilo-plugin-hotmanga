# evilo-plugin-hotmanga

A HotManga provider for evilo: a signed Kotlin/Wasmline plugin serving manga
search, metadata, chapter groups, and ordered image pages.

## Install in evilo

No signed release exists yet; the root catalog is empty until the release exporter
publishes the first version. Afterwards, subscribe to
`https://raw.githubusercontent.com/crowforkotlin/evilo-plugin-hotmanga/main/catalog.json`
from Browse's `+` action, not the repository page, then review the release and
approve its publisher fingerprint. Manifest signature and approved digest
verification stay mandatory.

## Commands

| Command | Purpose |
| --- | --- |
| `./gradlew :evilo-plugin-hotmanga-core:jvmTest` | Test requests, models, parsers, and continuations. |
| `./gradlew :evilo-plugin-hotmanga:assemble` | Compile the WASI guest. |
| `./gradlew -p evilo-plugin-build-logic :plugin:test` | Test the release build logic. |
| `git tag v1.0.0 && git push origin v1.0.0` | Publish through the release workflow. |

Sign and export a release locally; the version must equal `plugin-spec.json`:

```sh
./gradlew :evilo-plugin-hotmanga:exportPluginRelease \
  -Pevilo.signingKeyDirectory="$KEY_DIR" \
  -Pplugin.sourceRevision="$COMMIT" \
  -Pplugin.sourceUrl="$REPO_URL" \
  -Pplugin.releaseBaseUrl="$RELEASE_BASE_URL" \
  -Pplugin.publishedAt="$YYYY-MM-DD" \
  -Pwasmline.autoDownload=true
```

## Requirements

- Repository secret `EVILO_PRIVATE_KEY_HEX`: the preserved Ed25519 seed as 64 hex
  characters. Never commit or print it.
- `evilo-plugin-hotmanga/plugin-spec.json` is the only hand-edited release file.
- The SDK is `io.github.crowforkotlin.evilo:evilo-library`, pinned in
  `gradle/libs.versions.toml`. `-Pevilo.useLocalSdk` uses an unpublished local build.

No signed release or key is stored in this repository; the root catalog stays empty
until the release exporter writes the first version.

Step-by-step publishing: `docs/publishing.md`.
