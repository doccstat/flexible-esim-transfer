# Standalone native dependencies

The standalone backend builds these libraries from pinned source. They are
independent of any installed Xposed framework. The project code is MIT; retain
the dependency licenses when redistributing binaries/source. LSPlant is LGPL-3.0;
its complete corresponding source is provided here and the ZIP contains licenses.
Build recipes permit rebuilding/relinking the native module with modified sources.

| Directory | Upstream | Revision |
| --- | --- | --- |
| `vendor/lsplant` | https://github.com/JingMatrix/LSPlant | `7a768e31ffb2852797631b5140b54f9702a78743` |
| `vendor/lsplant/lsplant/src/main/jni/external/dex_builder` | https://github.com/JingMatrix/DexBuilder | `0c27690d88e217d1d2f0f53658cc140b43520b18` |
| `vendor/lsplant/lsplant/src/main/jni/external/dex_builder/external/parallel_hashmap` | https://github.com/greg7mdp/parallel-hashmap | `0cd57d29a959256ed66b2afdd1009928fc625d09` |
| `vendor/dobby` | https://github.com/JingMatrix/Dobby | `05a09ac6807a6bb1726350e40ea4b127c1c79809` |
| `include/zygisk.hpp` | https://github.com/topjohnwu/zygisk-module-sample | `8ce26128f81baaed0b969aaf7f52f886b61af4ab` |
| `vendor/xz-embedded` | https://github.com/tukaani-project/xz-embedded | `ae63ae3a36ed01724674e8f3d750dc47bf125410` |

LSPlant includes its JNI sources/license only; unused documentation/Gradle files
are omitted. DexBuilder includes its source/build files; parallel-hashmap includes
headers/build files/license. Dobby excludes hosted workflows and editor metadata. No dependency is
resolved from a moving branch during a build. The Zygisk header retains its license
and SHA-256 `f8d55e8b4f89d418c5941afe62ce6a09ddec1f4afd9a1b0a01eb40a93310dd28`.

XZ Embedded (0BSD) includes only its unchanged userspace decoder headers,
LZMA2/stream decoder, CRC32/CRC64 and license. Native builds enable CRC64 and
bounded dynamic decoding; no checksum is ignored. ART MiniDebugInfo input,
output and dictionary are each limited to 32 MiB; the outer ELF to 128 MiB.
Malformed, truncated, trailing or unsupported streams fail closed. Decoder
state is released after decoding, and symbol buffers after hook initialization.

The Java payload includes the existing Apache-2.0
`io.github.libxposed:api:101.0.0` API contract (`libxposed-api-LICENSE.txt`), not the framework or service implementation. A small local adapter
provides only the hook/chain/deoptimization/logging/read-only-preference methods
used by this project. The standalone ZIP does not load or require Vector.

`tools/native_build.py` compiles LSPlant/DexBuilder C++ modules directly with
Clang, leaving vendor source unchanged and avoiding a newer Ninja requirement.
`tools/build_zygisk.py` includes all licenses, the GPL-3.0 text incorporated by
LGPL-3.0, and a versioned corresponding-source URL in each ZIP. See
[rebuilding instructions](../docs/releasing.md#standalone-zip).
