# evilo-plugin-hotmanga

Standalone HotManga manga provider for evilo's synchronous Wasmline protocol.
The WASI guest binds `HotMangaPlugin` to `EviloPluginService`; the portable core
constructs requests and parses responses without a network client or host lookup.

## Publication status

No signed release has been built or published. The root `catalog.json` is an
empty, valid source until the release exporter supplies actual manifest bytes,
artifact hashes, source revision/archive/hash, and publisher public identity.
There is no signing key or sample trust key in this repository.

After the first release is published, share the directly served root catalog
URL, not a repository page. Subscription does not execute a plugin. Users must
approve the exact release and publisher fingerprint; manifest signature and
approved digest verification remain mandatory. NSFW is declared conservatively.

## Layout

- `evilo-plugin-hotmanga/`: signed release specification, WASI entrypoint, icons.
- `evilo-plugin-hotmanga-core/`: request construction, JSON models, parser,
  explicit continuations, and local fixture test sources.
- `evilo-plugin-build-logic/`: JVM/WASI conventions, signing and release export.
- `.github/`: public-release workflows and template helper scripts.

The canonical identity is `crow.evilo.source.hotmanga`, manga kind, Chinese
language, initial source version `1.0.0` / version code `1`. This is source
metadata, not evidence of a released package. The SDK remains the published
`io.github.crowforkotlin.evilo:evilo-library:2.0.0` artifact; no SDK is copied.
Use `-Pevilo.useLocalSdk` only after the matching SDK has been published locally,
or the template's explicit `-Pevilo.sdkPath` composite override.

## Provider contract

Evidence comes from Kira's `lib/api/api_transport.dart` (`routes`, request
interceptor), `lib/api/manga/manga_api.dart` (`getComicList`, `searchComics`,
`getComicDetail`, `getChapterList`, `getChapterDetail`), and
`lib/models/chapter.dart` (`ChapterDetail.fromJson`). These are manga paths, not
the cartoon paths in cutmangax-source's HotManga branch.

| Operation | Request |
| --- | --- |
| Empty search/catalog | `/api/v3/comics?free_type=1&ordering=-datetime_updated`, 21 items |
| Search | `/api/v3/search/comic`, UTF-8 `q`, `platform=3`, `free_type=1`, `_update=true`, 20 items |
| Details | `/api/v3/comic2/{path_word}?platform=3` |
| Chapters | `/api/v3/comic/{path_word}/group/{group}/chapters`, limit 100, actual-count offsets |
| Images | `/api/v3/comic/{path_word}/chapter/{uuid}?platform=3` |

Every operation uses the reviewed HTTPS `mapi.hotmangasg.com`,
`mapi.hotmangasd.com`, `mapi.hotmangasf.com`, `mapi.elfgjfghkk.club`,
`mapi.fgjfghkkcenter.club`, or `mapi.fgjfghkk.club` route family. Default is
`mapi.hotmangasg.com`. CopyManga's `api.copy*` list hosts are not accepted.
Requests use Kira's Android browser User-Agent, `Accept: application/json`,
`platform: 3`, `version: 2024.04.28`, `webp: 1`, and
`X-Requested-With: com.manga2020.app`. They do not use CopyManga's app version,
`source`, HMAC, or `q_type` marker. The reference's request `Content-Encoding`
is not copied: GET has no compressed body. Cookies, Authorization, Host,
Connection, Origin, forwarding headers, device analytics and proxies are not
added. Login/account storage and restricted-content access are not implemented.

Details preserve plugin-qualified IDs and map title, authors, tags, cover and
brief. Groups are carried in a five-minute, per-item snapshot, with default first.
Chapter batches retain group/number metadata and server order. Missing `words`
uses every returned image in server order, as Kira's chapter parser does; an
optional legacy permutation is validated before reordering. CDN URLs are retained
verbatim except protocol-relative HTTPS normalization, and are restricted to
`s0`, `s3`, and `sa` through `sz` under `mangafunb.fun` from the supplied image
contract. Unknown media origins fail instead of being silently approved.

HTTP transient failures, invalid envelopes and null/missing image payloads retry
each reviewed mirror once for that request. Business rejection, locks, identity
mismatch, malformed values and invalid image ordering are reported directly.
Network failure before a `SourceResponse` cannot resume a guest under SDK 2.0.0;
DNS/connection/host timeouts remain host errors. No claim of current live API
availability is made from these reference sources.

Continuations carry operation identity, exact request URL, origin attempts,
groups, offset and counts; there is no mutable last-request state. Limits are
512 HTTP exchanges, 128 KiB continuation, 256 KiB operation, 2 MiB API body,
4 MiB encoded result, 64 groups, 20,000 chapters and 2,000 images. Host HTTP
approval, redirect restrictions, response limits and coroutine cancellation are
not bypassed. Synchronous guest code catches only its provider exception or
serialization failures, never a host cancellation.

SDK 2.0.0 has no serialized per-origin permission field. The plugin's reviewed
allowlists are restrictions on its own requests, not a replacement host permission
contract. Do not claim that an ignored `permissions` block grants or enforces
access. Browsers still apply CORS and mixed-content rules; Wasm does not bypass them.

## Licensing and attribution

MIT; see `LICENSE`. Portable JSON/mapping/continuation code is adapted from
`evilo-plugin-copymanga`; build and release tooling is adapted from
`evilo-plugin-template`. Their copyright and permission notice is retained.
Kira (copyright 2026 孤独的Lonely, MIT) is the reference for the HotManga endpoint,
header and response contract. cutmangax-source (Apache-2.0) is a read-only
reference for current shared image-host and pagination observations; its Android,
JNI, account, proxy and signing implementations are not copied here. No claim is
made that a software license grants redistribution rights for provider content.

## Signing and export

Follow `docs/publishing.md` and `.github/README.md`. Supply an existing,
publisher-owned Ed25519 identity outside every repository. Never reuse a sample
or another publisher's key. Release export refuses missing/mismatched authority
files and computes SHA-256 from actual manifest/source/artifact bytes. Published
versions are immutable; changed source requires a new version and higher code.

No build, test, formatter, linter, live API call, server or device operation was
run for this implementation. Manual commands, requiring JDK 21 and compatible
Node.js for WASI, are listed below for separate authorization.

```sh
./gradlew :evilo-plugin-hotmanga-core:jvmTest -Pevilo.useLocalSdk
./gradlew :evilo-plugin-hotmanga-core:wasmWasiNodeTest -Pevilo.useLocalSdk
./gradlew -p evilo-plugin-build-logic :plugin:test
```

After committing the intended source, export only with actual operator inputs:

```sh
./gradlew :evilo-plugin-hotmanga:exportPluginRelease \
  -Pevilo.signingKeyDirectory="$PUBLISHER_SIGNING_DIRECTORY" \
  -Pplugin.sourceRevision="$SOURCE_COMMIT" \
  -Pplugin.releaseBaseUrl="$RELEASE_BASE_URL" \
  -Pplugin.sourceUrl="$SOURCE_REPOSITORY_URL" \
  -Pplugin.publishedAt="$PUBLISHED_AT" \
  -Pwasmline.autoDownload=true
```

The exporter writes under
`evilo-plugin-hotmanga/build/release/crow.evilo.source.hotmanga-1.0.0/`.
The release workflow requires explicit manual dispatch of an existing source
tag; normal pushes and tag pushes never publish. It uses only exported public
files; the empty root catalog
contains no invented installable version, signing identity or hash.
