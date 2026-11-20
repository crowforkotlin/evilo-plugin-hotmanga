# Publishing a signed HotManga release

The repository has no published package yet. `catalog.json` intentionally lists
no plugin until an actual export supplies signed files and measured hashes.
Configuration below is not evidence of a completed signing or publication.

## Publisher identity

Use an existing publisher-owned Ed25519 seed. Do not copy evilo's official key,
another plugin's seed, or a sample public key. Keep the authority directory
outside repositories and source archives; never print or commit the seed.
Manual export requires matching `private.key`, `public.key`, and
`authority.properties` files. `checkPluginAuthority` rejects missing, symlinked,
repository-local or mismatched identity files; release tasks never generate keys.

The workflow reads repository Secret `EVILO_PRIVATE_KEY_HEX` and optional
Variable `EVILO_KEY_ID`. `publisher_key.py` restores the seed only into the
runner's external temporary directory and derives public metadata from it.
Default key label is `publisher-<public-key-fingerprint>`. There is no copied
sample trust key, official-key identity workflow or automatic trust grant.
Users review the publisher fingerprint and exact release in evilo; its signed
loader verifies and pins the manifest. A software signature is not network
permission or evidence that the code is safe.

## Source identity

Edit `evilo-plugin-hotmanga/plugin-spec.json` before a new release. Keep plugin ID
`crow.evilo.source.hotmanga`. `descriptor.version` must match a new `vX.Y.Z`
source tag and `descriptor.versionCode` must exceed every published code.
Never move an old tag or replace exported bytes for an existing release.
The first source version is `1.0.0`, code `1`; it is not yet a signed release.

The coordinator/operator owns repository creation, remote setup, commits,
pushes, tags and release dispatch. None was performed during implementation.

## Manual commands

JDK 21 and a compatible Node.js are required. Tests and export are separate
operations, requiring separate authorization. No command in this section was run.

```sh
./gradlew :evilo-plugin-hotmanga-core:jvmTest -Pevilo.useLocalSdk
./gradlew :evilo-plugin-hotmanga-core:wasmWasiNodeTest -Pevilo.useLocalSdk
./gradlew -p evilo-plugin-build-logic :plugin:test
```

The local-SDK option assumes the matching SDK has already been published locally.
After committing the intended source, export with actual operator-supplied inputs:

```sh
./gradlew :evilo-plugin-hotmanga:exportPluginRelease \
  -Pevilo.signingKeyDirectory="$PUBLISHER_SIGNING_DIRECTORY" \
  -Pplugin.sourceRevision="$SOURCE_COMMIT" \
  -Pplugin.releaseBaseUrl="$RELEASE_BASE_URL" \
  -Pplugin.sourceUrl="$SOURCE_REPOSITORY_URL" \
  -Pplugin.publishedAt="$PUBLISHED_AT" \
  -Pwasmline.autoDownload=true
```

The task computes every SHA-256 from actual bytes, embeds the exact source
revision/archive digest in signed metadata, and exports manifest, artifacts,
source ZIP, icon and versioned catalog. Never invent a hash or hand-edit a signed
release. Source ZIPs exclude caches, build outputs, local configuration and keys.

## Workflow and recovery

`.github/workflows/plugin-release.yml` packages only an explicitly dispatched
existing source tag, using the template's split package/publish jobs. Ordinary
pushes and tag pushes do not trigger signing or publication.
The package job has read-only repository permissions and removes restored private
files even after failure. The publish job receives public files only, creates
immutable `package-vX.Y.Z` files and appends the generated release to the root
catalog. Share the directly served root catalog URL after publication.

The optional `republish=true` dispatch restores missing public Release assets
from immutable package files without reading a seed, rebuilding, re-signing or
changing the catalog. Existing differing bytes are rejected. See
`.github/README.md` for the retained template helper contracts.
