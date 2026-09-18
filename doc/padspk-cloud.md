# Pad speaker (padspk) audio on PS Cloud

Working notes for the DualSense pad-speaker lane: what the protocol looks like, what
this branch implements, what is still missing, and how to pick the work back up.

Everything below was read out of PS5 firmware 12.40. Two binaries carry almost all of
it:

| role | file |
|---|---|
| cloud client (PS5 playing a cloud game) | `system_ex/app/NPXS40099/gaikai-player.sprx` |
| remote play host / Takion daemon | `system/vsh/app/NPXS40102/eboot.bin` |

Both are plain unencrypted x86-64 ELFs with no section headers, so addresses below are
virtual addresses, and a disassembler needs the program headers to map them. The client
is compiled with optimisation in places and without it in others; the unoptimised parts
(`ProsperoAudioState`, `ProsperoAudioDecoder`) read almost like source.

---

## 1. The channel model

The console maps a channel *kind* plus a controller index onto one flat channel id. That
id is what travels in `AudioChannelPayload.audioChannelType` and in the per-packet
channel byte of a v12 Takion AV audio header.

```
main          -> 0
voice         -> 1
haptic, pad n -> 2 + n      (2..5)
padspk, pad n -> 6 + n      (6..9)
                 10          = channel count / "no such channel"
```

From the client's allocator at `0x84ec0`:

```c
uint32_t audioChannelId(const std::string &kind, int padIndex) {
    if (kind == "main")   return 0;
    if (kind == "voice")  return 1;
    if (kind == "haptic") return padIndex + 2;
    if (kind == "padspk") return padIndex + 6;
    return 10;
}
```

This is also why chiaki's long-standing `is_haptics = (*av == 0x02)` worked: 2 is haptic
pad 0, not a magic constant. The string table `{main, voice, padspk, haptic}` that
appears 24x across the firmware is the kind->name array, *not* the id order — reading it
as the enum is the easy mistake here.

### Lane formats

Straight out of the host's `declareChannel` at `0x21d5a0`:

| lane | rate | ch | samples/frame | bytes/frame | isRawPcm | bitrate |
|---|---|---|---|---|---|---|
| haptic | 3000 | 2 | 30 | 120 | **true** | ~96 kbps |
| padspk | 48000 | 1 | 480 | 960 | **false** | ~48 kbps |

The two per-controller lanes are *not* alike. Haptics is raw PCM — which is why
chiaki's haptics path can `memcpy` `int16_t` straight out of the buffer, and why its
`buf_size != 120` check is exactly right. **padspk is Opus** and has to be decoded.

The 120 is the giveaway that confirms the whole table: it matches chiaki's existing
assertion independently. Note also that haptics' 96 kbps is exactly its raw bitrate
(3000 x 2 x 16), while padspk's 48 kbps is nowhere near its raw 768 — a second,
independent sign that padspk is compressed.

Channel entries are 36 (`0x24`) bytes:

```
+0x00 dword  audioChannelType        +0x14 dword  channels
+0x04 dword  (5)                     +0x18 dword  maxFrameDataSize
+0x08 byte   isSigned                +0x1c dword  samplesPerFrame
+0x09 byte   isRawPcm                +0x20 dword  bitrate (kbps)
+0x0c dword  sampleRate
+0x10 dword  sampleSize (bytes)
```

### Protocol schema

The real fw 12.40 `takion.proto` was recovered from the embedded `FileDescriptorProto`
at client file offset `3842416` (10755 bytes, 51 messages). Two things this tree was
missing:

```proto
message AudioChannelPayload {
    required uint32 audioChannelType = 1;
    required bytes  audioHeader      = 2;
    optional bool   isRawPcm         = 3;
    repeated bytes  profileHeader    = 4;   // was missing
    optional uint32 fecMode          = 5;   // was missing
}
```

and payload type **`AUDIOSTATE = 33`** with `optional AudioStatePayload audioState = 33`,
which the enum here stopped short of entirely (it ended at 32).

---

## 2. The version gate is a feature table

Neither end gates on version ranges. Both use one shared predicate — client `0x2511f0`,
host `0x169910` — where each protocol version carries a *feature count* and a feature is
available when its id is below it.

Counts for v9..v20: `5, 6, 7, 8, 8, 8, 13, 13, 13, 16, 17, 19`. Versions 10-12
additionally mask out a few low ids (`0x2f`, `0x5f`, `0xdf`). **A version above 20 is
rejected outright, not treated as newer** — so v23 gets nothing on this firmware.

| feature | first version | what it gates |
|---|---|---|
| 6 | 11 | parsing the `audioChannel` list at all |
| 7 | **12** | haptics |
| 10 | **15** | padspk channels **and the entire AUDIOSTATE message** |
| 15 | **18** | AUDIOSTATE type CHANNELNUM |
| 16 | **19** | audio port mask wider than `0xff` |

Feature 7 landing on v12 is the self-check: v12 is what this client negotiates for PS5,
and haptics works today. Implemented as `chiaki_takion_protocol_feature_supported()`.

That feature 10 gates AUDIOSTATE *as a whole* is the important part — see §4.

---

## 3. AV header sizes per version

From the client's lookup tables (`0x251350`, `0x251370`, `0x2513c0`), indexed by
`version - 9`:

```
version : 9   10  11  12  13  14  15  16  17  18  19  20
base    : 11  11  11  11  11  11  11  11  11  11  11  19
audio   : 18  18  19  19  19  19  20  20  20  20  20  28
video   : 23  23  23  23  23  23  23  23  23  23  23  31
```

These validate against chiaki's own constants — v9 audio 18 = `V9_..._AUDIO` (0x12),
v12 audio 19 = `V12_..._AUDIO` (0x13), video 23 = 0x17 — which is what makes the rest of
the row trustworthy.

Reading it: **v15-19 is v12 plus exactly one byte, in the audio-specific part** (base and
video unchanged). **v20 adds 8 bytes to the common prefix**, so audio and video both grow
by 8 on top of that. A fourth table (v15-20 only) reads `21,21,21,21,21,29` = audio + 1,
matching chiaki's `av_header_size + 1` minimum-payload check.

What the extra v15-19 audio byte *means* is not established — only its size and that it
is in the audio-specific region.

Recorded in `takion.h` as `CHIAKI_TAKION_AV_HEADER_SIZE_*_TABLE`.

**ECDH:** both binaries contain exactly two curve setups, as adjacent sibling pairs:
`secp256k1` (NID 714) and `secp521r1` / P-521 (NID 716). The client contains **no P-256
at all** — worth noting, since `ecdh.c` here uses `prime256v1` for the cloud path. It
evidently works at v9/v12, so this is an observation, not a known bug.

---

## 4. AUDIOSTATE: what the host is actually waiting for

Declaring the padspk channels gets them *allocated*. It does not get them *fed*. The
missing half is `AUDIOSTATE`, the only channel through which a client can tell the host
that an audio port or channel is live.

From `Connection::sendAudioState` at `0x2af530`:

```c
if (!capability(10 /* padspk */, takion->version)) return false;
if (stateType == 6 /* CHANNELNUM */)
    if (!capability(15, takion->version)) return false;
msg->type = 0x21;  // AUDIOSTATE
```

The host drops **every** audio state update — of every type — unless the negotiated
version carries the padspk feature. So the same version step that makes the lanes
declarable is what makes them requestable. Channels allocated but silent is exactly what
that produces.

### Blob layouts

`ProsperoAudioState` (ctor `0x31540`, poll `0x31700`, port states `0x321e0`) polls the
client's own audio hardware, caches each result, and sends only what changed — the diff
is what selects the type.

| type | size | layout |
|---|---|---|
| **HRTF** (3) | 4 | the HRTF id, uint32 |
| **FLAGS** (1) | 9 | speaker-kind byte, then two dwords |
| **ANGLE** (2) | 64 | 16 speaker positions, two uint16 each |
| **FULL** (0) | 73 | FLAGS' 9 bytes, then ANGLE's 64 |
| **TVCONFIG** (4) | 188 | a dword, two 90-byte correction blocks, then 2 + 2 bytes |
| **PORTSTATES** (5) | 33 | group byte, then that group's 32-byte port block |

Three pin themselves independently: HRTF is a lone 4-byte id, TVCONFIG is 188 = exactly
the `0xbc` buffer the client allocates for every blob, PORTSTATES is 33.

All fields are **native byte order** — the client `memcpy`s straight out of the
sceAudioOut structs rather than serialising. This is unlike the big-endian audio header.

The **port availability mask** is the dword at **offset 1** of a FLAGS or FULL blob. That
is what `sendAudioState` clamps to `0xff` without feature 16.

> Quirk worth knowing: the client applies that clamp for `stateType <= 2`, which includes
> ANGLE — but ANGLE carries no mask at offset 1, only packed positions, so clamping there
> corrupts them. Looks like `<= 2` where `<= 1` was meant. This tree clamps only FLAGS and
> FULL. It is client-side only, so that is strictly safer, not a compatibility risk.

### PORTSTATES in detail

```c
void updatePortStates(this, uint8_t group, const int32_t handles[4], void *blob) {
    uint8_t *state = (uint8_t*)this + 0x154 + group*32;
    uint8_t prev[32]; memcpy(prev, state, 32); memset(state, 0, 32);
    for (int i = 0; i < 4; i++) {
        if (handles[i] < 0) continue;              // -1 = port not open
        sceAudioOutGetPortState(handles[i], &st);
        *(uint16_t*)(state + 0x00 + i*2) = st.output;
        *(uint16_t*)(state + 0x08 + i*2) = st.volume;
        *(uint32_t*)(state + 0x10 + i*4) = (uint32_t)st.flag;
    }
    if (memcmp(state, prev, 32)) {                 // ymm compare
        blob[0] = group;
        memcpy(blob + 1, state, 0x20);
        sendAudioState(session, 5 /* PORTSTATES */, blob, 0x21);
    }
}
```

The 32-byte block is **field-major, not port-major**:

```
05 | 1111 4444 7777 aaaa | 2222 5555 8888 bbbb | 33333333 66666666 99999999 cccccccc
^grp      4x output            4x volume                4x flag
```

The client polls **two groups of four ports** — eight total, which is what an 8-bit
availability mask covers.

---

## 5. How a channel becomes a live output

`ProsperoAudioDecoder::openDevice` at `0x32940` maps a channel onto a local
`sceAudioOut` port and opens it:

```c
if (audioChannelType == 1) {                       // VOICE
    portType = 2;  param = 1;
    sceUserServiceGetInitialUser(&userId);
}
else if (isPadSpeakerChannel(audioChannelType)) {  // 0x33990
    portType = 4;  param = 0;
    sceUserServiceGetLoginUserIdList(&users);
    userId = users[ padIndexForChannel(audioChannelType) ];   // 0x339c0
}
handle = sceAudioOutOpen(userId, portType, 0, 256, freq, param);
```

with

```c
bool isPadSpeakerChannel(int t) { return t >= 6 && t <= 9; }   // 0x33990
int  padIndexForChannel(int t)  { return (t - 2) & 3; }        // 0x339c0
```

That `(t - 2) & 3` is one expression serving both per-pad ranges, folding haptic 2-5 and
padspk 6-9 onto 0-3 alike.

Port type **2** for the unambiguous voice branch and **0** for the default identify the
numbering as `SceAudioOutPortType`, which puts the pad speaker at **4**.

**So the full chain is:**

```
channel 6..9 declared
  -> local output port of type 4 opened for login user (channel-2)&3
    -> that port's state reported in an AUDIOSTATE PORTSTATES message
      -> host produces for the lane
```

A lane is fed because a port is **open and reported**, not because a channel was
declared.

---

## 6. `sceAudioOutGetPortState` and its struct

The import was resolved from the binary rather than by guessing NIDs (a computed-NID
approach failed to reproduce even known values):

- stub `0x30d510` is `jmp qword ptr [rip+0xb049a]` -> GOT `0x3bd9b0`
- the client's dynamic tables are plain ELF: `DT_STRTAB 0x3cd390`, `DT_SYMTAB 0x3d13d0`,
  `DT_JMPREL 0x3d6fb0`
- the JMPREL entry for that GOT slot names symbol 264: **`GrQ9s4IrNaQ#H#I`**

The suffix is `NID#libraryId#moduleId` in base64url. Sorting all the client's audio
imports that way:

```
libId 17  libSceAudioOut2   modId 8  libSceAudioOut   (15 symbols)
libId  7  libSceAudioOut    modId 8  libSceAudioOut   ( 6 symbols)
```

**`libSceAudioOut2` is not a separate module.** Both libraries come out of one module,
`libSceAudioOut` — which is why the console's file index lists `libSceAudioOut.sprx` and
no `libSceAudioOut2.sprx`. (Same pattern as `libScePosix`/`libkernel` here.)

That distinction settles the call. Every audio import whose name is known independently,
from its own error string, lands in the library its name implies:

| library | functions |
|---|---|
| `libSceAudioOut2` (17) | `GetSpeakerInfo`, `GetHrtfIdForCronos`, `GetTvCorrectionInfo` |
| `libSceAudioOut` (7) | `sceAudioOutOpen` |

`GrQ9s4IrNaQ` sits in library 7 beside `sceAudioOutOpen`, so it is the v1
**`sceAudioOutGetPortState`** — not a `sceAudioOut2` function.

**This was since confirmed against a libSceAudioOut symbol table** (a genstub NID map,
from fw 3.2): `GrQ9s4IrNaQ` -> `sceAudioOutGetPortState`, `ekNvsT22rsY` ->
`sceAudioOutOpen`, `DImz2Ft9E2g` -> `sceAudioOut2GetSpeakerInfo`. The library-placement
argument above held.

Its `SceAudioOutPortState` is 32 bytes, matching the caller's stack slot (`rbp-0x48` to
`rbp-0x28`) derived separately, and the three offsets read land on named fields:

```c
typedef struct SceAudioOutPortState {
    uint16_t output;          // +0x00  <- copied
    uint8_t  channel;         // +0x02
    uint8_t  reserved;        // +0x03
    int16_t  volume;          // +0x04  <- copied
    uint16_t rerouteCounter;  // +0x06
    uint64_t flag;            // +0x08  <- copied (low half)
    uint64_t reserved64[2];   // +0x10
} SceAudioOutPortState;       // 32 bytes
```

### The client's full audio API surface

Resolving every audio import the same way gives the complete picture of what the cloud
client does with local audio. 19 of 21 resolve directly; the two that do not are
PS5-only and were already named from the binary's own error strings.

| stub | NID | function | observed use |
|---|---|---|---|
| `0x30d4d0` | `DImz2Ft9E2g` | `sceAudioOut2GetSpeakerInfo` | AUDIOSTATE FLAGS/ANGLE source |
| `0x30d4f0` | `RsOQBASFo68` | `sceAudioOut2GetHrtfIdForCronos` * | AUDIOSTATE HRTF source |
| `0x30d500` | `e9rTn1fwgbQ` | `sceAudioOut2GetTvCorrectionInfo` * | AUDIOSTATE TVCONFIG source |
| `0x30d510` | `GrQ9s4IrNaQ` | `sceAudioOutGetPortState` | AUDIOSTATE PORTSTATES source |
| `0x30d520` | `JfEPXVxhFqA` | `sceAudioOutInit` | decoder ctor |
| `0x30d530` | `QOQtbeDqsT4` | `sceAudioOutOutput` | teardown, `(handle, NULL)` |
| `0x30d540` | `s1--uE9mBFw` | `sceAudioOutClose` | teardown |
| `0x30d590` | `ekNvsT22rsY` | `sceAudioOutOpen` | `openDevice`, port type 4 for padspk |
| | `b+uAV89IlxE` | `sceAudioOutSetVolume` | |
| `0x30d550`..`0x30d570` | | `sceAudioOut2{Port,User,Context}Destroy` | 3D-audio path teardown |
| | | `sceAudioOut2Initialize`, `ContextCreate`, `UserCreate`, `PortCreate`, `PortSetAttributes`, `ContextAdvance`, `ContextPush`, `ContextQueryMemory`, `ContextResetParam` | `openDevice2`, the >8-channel path |

\* named from the binary's own error strings; absent from the fw 3.2 map because they are
PS5-only.

Note the teardown idiom this confirms: `sceAudioOutOutput(handle, NULL)` to drain, then
`sceAudioOutClose(handle)`.

### Inside libSceAudioOut

The module itself was later obtained, so the following is read off the implementation
rather than inferred.

**Legal port types.** `sceAudioOutOpen` validates the type against the bitmask
`0x441f`, i.e. types **0, 1, 2, 3, 4, 10, 14** (plus `0x7d` and `0x7f` as special
cases). Type 4, the pad speaker, is a first-class type, not a value smuggled through a
general path.

**`sceAudioOutGetPortState` (`0x2460`)** validates `(handle & 0x7f000000) == 0x20000000`
with a port index of `handle & 0xffff` below `0x21` (33 ports), then fetches the state
over IPC from the audio daemon and post-processes it:

```c
state->output  = daemon.word0;
state->channel = daemon.byte2;
if (capability_ok()) {
    if (state->output & 1)                     // bit 0 implies bit 1
        state->output |= 2;
    if (port->type == 2 /* VOICE */ && (state->output & 4))
        state->output = (state->output & ~7) | 3;
}
state->rerouteCounter = daemon.word6;
state->flag = capability_ok() ? daemon.dword8 : 0;

state->volume = 0xffff;                        // <-- note
if (port->type == 4 /* PADSPK */)
    state->volume = daemon.word4;
```

Two things matter here.

**Volume identifies a pad speaker port.** Every port type reads back `0xffff` for volume
*except* type 4, which is the only one whose real volume is reported. So within a
PORTSTATES group, the pad speaker port is the one whose volume is not `0xffff`. Recorded
as `CHIAKI_AUDIO_OUT_VOLUME_NOT_REPORTED`.

`sceAudioOutSetVolume` shows the same asymmetry from the other side: for a type-4 port it
converts the integer to float, scales and clamps it, and sends it to the daemon as its
own parameter (id `0xa`); every other type goes down a different path.

**`output` bit rules**, though not the constant names: bit 1 is set whenever bit 0 is,
and a voice port with bit 2 set has its low three bits forced to 3. The values
themselves originate in the audio daemon over IPC, so the module gives the rules but not
the enum.

Port table internals, if needed again: base `0x6cf98`, stride `0x1040`, port type at
`+0x1020`, refcount at `+0x102c`, a disable bit at `+0x103a & 0x40`.

---

## 7. Retail remote play cannot do this

Not a gate — a missing call.

The host holds four AvCap consumer slots and releases all four in its destructor, but its
factory pointer (`this->+0x17c0`) is touched **exactly seven times in the whole image**:

| site | what |
|---|---|
| `0x225930` | the setter |
| `0x21c67c` | create video -> vtable `+0x10` -> slot `+0x418` |
| `0x21c9f5` | create main audio -> `+0x18` -> slot `+0x420` |
| `0x21cb5f` | create haptics -> `+0x20` -> slot `+0x428` |
| `0x21ac43`, `0x21ac67`, `0x21ac96` | three releases -> `+0x28` |

Three creates. **Slot `+0x430` — the one the host's own padspk capture section reads at
`0x22288a` — has no creator anywhere.** Everything else for the lane is present: format
declaration, capture thread section (`AvCap audioThread padspk`), the `mixToMain` fork,
per-channel active flags, error logging. There are init-failure strings for `haptic`,
`video` and `audio` and none for padspk, because there is no init to fail.

Supporting detail: the retail host has **no log strings for handling audio state at all**,
only the protobuf descriptor entries that come free with the shared schema — consistent
with AUDIOSTATE being acted on by the cloud server rather than this binary.

The cloud server is a different, datacenter-side build, so none of this binds it. Cloud
remains the only viable target.

There is also a second, lesser blocker: feature 10 needs v15+, and remote play negotiates
v12 (`streamconnection.c:276`).

### The `mixToMain` gate

Host-side, at `0x222a1f`, immediately before the padspk section:

```
cmp byte [rbx + r13 + 0x1810], 1   ; per-channel active flag
jne skip
cmp byte [rbx + 0x214], 1          ; mixToMain?
jne separate_lane                  ; not mixing -> padspk gets its own lane
... fold mono pad audio into the stereo main mix ...
```

`mixToMain` comes from the host's `audioSettings` JSON (parsed at `0x28ec8a`). When true
the pad audio is already in the main mix, so asking for the lane as well would double it.

---

## 8. What this branch implements

| commit | content |
|---|---|
| `a8d1f70` | channel-id model, per-lane routing, padspk receivers, advertisement, Android path |
| `498890b` | codec correction (padspk is Opus), AV header size tables |
| `9364738` | feature table, `AudioStatePayload` in the proto, AUDIOSTATE sender |
| `ab5829b` | all five AUDIOSTATE blob builders |
| `0ff240a` | channel -> port type mapping |
| `cf42a2f` | port state struct shape |
| `e09a393` | port state field names |

Concretely:

- **`takion.c`** — the channel byte is kept as `ChiakiTakionAVPacket.audio_channel`;
  `is_haptics` now means "in 2..5"; muting stream audio no longer kills padspk (haptics
  was already exempt); `chiaki_takion_protocol_feature_supported()`.
- **`audioreceiver.c`** — dispatches on channel id to a per-pad padspk sink.
- **`pscloud_audio_reassembler.c`** — tracks channel per unit instead of a haptics bool;
  FEC-recovered units inherit the generation's channel instead of silently defaulting to
  main (that was a latent bug).
- **`streamconnection.c`** — one receiver per padspk lane; the advertisement in the mic's
  audio-settings message, gated on cloud + feature 10 + `!mixToMain`;
  `chiaki_stream_connection_send_audio_state()` with the verified gates and mask clamp.
- **`audio.c` / `audio.h`** — the channel model, lane formats, port-type mapping, and the
  five blob builders.
- **Android** — `PadSpeakerAudioRouter` feeds the existing DualSense BT speaker FIFO;
  per-pad mono Opus decoders live in the JNI so Kotlin only ever sees PCM; follows the
  host's player index; off by default behind `controller_pad_speaker_lane`.

Verification status: all of `lib/` compiles clean (the only failures are pre-existing
missing third-party headers — jerasure, opus, ffmpeg — plus one pre-existing `const`
warning in `send_big`). Blob layouts and the port-type mapping were checked byte-for-byte
with throwaway harnesses. **The JNI was only reviewed by hand** — there is no Android SDK
in the environment this was written in, so the Opus decode path is uncompiled.

---

## 9. What is left

Two things, in order.

**1. The port-state values.** The layout is exact; the semantics of what to put in
`output` and `flag` for a pad-speaker port are not established. These are
`SCE_AUDIO_OUT_STATE_OUTPUT_*` and `SCE_AUDIO_OUT_STATE_FLAG_*` constants. `output` is the
interesting one — it says where the port's audio is going, so for a type-4 port it is
presumably what marks it as pad-speaker bound.

Two ways to close it:

The module has since been read (see "Inside libSceAudioOut" above), which settled part of
this and bounded the rest:

- **Settled:** the legal port types, and that `volume` reads `0xffff` for every port type
  except the pad speaker — which is itself a usable signal.
- **Still open:** the `output` and `flag` constant values. These do not live in
  libSceAudioOut at all; it receives them from the audio daemon over IPC and only
  post-processes them. What the module does give is their *rules* (bit 0 implies bit 1;
  voice plus bit 2 forces the low three bits to 3).

So the remaining sources for the constants are an SDK header, or `orbis_audiod` /
`libSceAudioSystem` on the daemon side of that IPC. A genstub `.c` is no use here: those
carry NID -> name mappings only, no `#define`, `enum` or `struct`.
- **Brute-force.** `output` is 16 bits with few meaningful values, `volume` is obvious,
  `flag`'s low half is likely small. The host gives a clean per-attempt yes/no, and no
  capture decryption is needed. Practical now that the search space is three named fields
  rather than three opaque words.

**2. Takion v15-20.** Even with correct PORTSTATES, a session must negotiate v15+ for any
of it to be accepted. That needs:

- the AV parser extended — v15-19 is one extra byte in the audio-specific header region,
  v20 is 8 extra in the common prefix (§3); the byte's meaning is still unknown
- P-521 ECDH alongside the existing curves (§3)
- the negotiated version raised past 12 (`streamconnection.c:276`, `senkusha.c:175`)

Until then the padspk code is correct but dormant: it refuses cleanly and logs why rather
than failing silently.

---

## 10. Reproducing the analysis

Tooling used was a small Python harness over `capstone`, with segment maps taken from each
binary's program headers (`readelf -l`), since neither binary has section headers.
`objdump -d` produces nothing on them for that reason; disassembly must be done on raw
bytes at the right virtual address.

Two traps worth flagging for anyone picking this up:

- **Linear sweep misaligns.** A whole-image linear disassembly from offset 0 produces
  phantom cross-references in places. Anything load-bearing should be re-read from a
  confirmed function prologue (`55 48 89 e5`), not from the sweep.
- **Function-start detection by `int3` padding is unreliable.** Several real functions sit
  behind padding that the heuristic attributes to the previous function, and some jump
  islands look like starts. `0x32420` in particular is the `ProsperoAudioDecoder`
  constructor, not the port-open function; the port-open function is `0x32940`.

Useful anchors:

```
client  0x84ec0   channel-id allocator          0x2511f0  feature predicate
        0x31540   ProsperoAudioState ctor       0x31700   poll / FLAGS / ANGLE / HRTF / TVCONFIG
        0x321e0   PORTSTATES                    0x32940   openDevice (port open)
        0x33990   isPadSpeakerChannel           0x339c0   padIndexForChannel
        0x2af530  Connection::sendAudioState    0x8fe60   mask clamp
        0x251350/0x251370/0x2513c0              AV header size tables
host    0x169910  feature predicate             0x21d5a0  declareChannel
        0x21b720  AvCap init                    0x221b80  audio thread (main/haptic/padspk)
        0x28ec8a  audioSettings JSON parser     0x21ac40  consumer destructor
```
