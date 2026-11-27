# evilo-plugin-hotmanga

evilo plugin for HotManga.

## Commands

Build and test with JDK 21:

```sh
./gradlew :evilo-plugin-hotmanga-core:jvmTest
./gradlew :evilo-plugin-hotmanga:assemble
```

## Configure

- `evilo-plugin-hotmanga/plugin-spec.json`: metadata, version, filters.
- GitHub secret `EVILO_PRIVATE_KEY_HEX`: publisher Ed25519 seed (64 hex characters).
- Publish: raise `descriptor.version` and `versionCode`, push tag `v<version>`. See [docs/publishing.md](docs/publishing.md).

Catalog: https://raw.githubusercontent.com/crowforkotlin/evilo-plugin-hotmanga/main/catalog.json
