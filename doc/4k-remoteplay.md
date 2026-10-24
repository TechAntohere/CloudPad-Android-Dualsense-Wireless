# 4K / 1440p on PS5 Remote Play

Working notes for raising Remote Play past 1080p. Three PS5 host builds are involved and
they do not share offsets, so every address below is tagged with the build it came from.
Provenance is kept explicit because the conclusion rests on it.

| build | tag / SHA-256 | source | role here |
|---|---|---|---|
| J03778134 | `e83f6352…9d35ae5` | this corpus (`NPXS40102/eboot.bin`) | what *this* session can disassemble and byte-verify |
| 12.00 | `NPXS40102_1200.elf` | user handoff `RESOLUTION_GATES.md` | first gate map |
| 13.60 | `674652A0…6944BE` | user handoff `..._HANDOFF_2.md` | fullest map; the three-gate chain |
| 13.60 AvCapture | `83160C4E…65AC3B` | user handoff | NPXS40100 — **not in this corpus** |

The layouts split into two families. The 12.00 build keeps the "supports 4K" flag at
`config+608`. The J03778134 build in this corpus and the 13.60 build both keep it at
`session+0x260` — so the corpus build is layout-aligned with 13.60, and the offsets below
that are marked "verified here" were read out of J03778134 directly.

---

## Bottom line

4K/1440p Remote Play is a **console-side software gate, not a hardware limit** — the same
silicon streams 4K for PS Plus cloud. Of the three gates, the client controls two and the
console controls the third, and the third is set from a kernel capability query that a base
PS5 answers false. So:

- **No stock-firmware path exists.** Independently reached by all three builds. Nothing a
  client sends — launchspec, protobuf, Takion field, RPC — reaches the capability flag.
- **With a jailbroken console the gates are byte-patchable**, but the decisive one is not a
  byte patch: it is a memory-pool allocation that must run inside the capture service
  process. That is the whole engineering task; everything else is one-byte edits.
- **1440p is the tractable target** (two gates, no Neo branch). **2160p needs all of them.**

---

## The gate chain

### Gate 0 — client inputs (no patch, do these regardless)

Two of the three policy inputs are the client's to set and need no console change:

1. **Offer the resolution.** The host advertises only what the launchspec's
   `requestGameSpecification.resolution` list contains. Add `2560×1440` and `3840×2160`
   entries (chiaki-ng: `lib/src/launchspec.c`).
2. **Negotiate HEVC** (`videoCodec:"hevc"`). Mandatory — without it even 1440p is filtered
   on every build. Codec enum must be 2 or 3; fps 30 or 60.

The host carries the fixed 6-entry resolution table; **verified here** in J03778134 in
rodata (descending): `3840×2160` at `0x4f3630`, `2560×1440` at `0x4f3638`, down to
`640×360`. The 13.60 validator at `SceRemotePlay+0x6F840` accepts indices 5 and 6
explicitly. So the dimensions are recognised; what follows is permission, not capability.

### Gate A — the 2160 admission filter (the Neo gate)

The launchspec normalizer filters each offered resolution before it is advertised. 2160
additionally requires a "supports 4K" permission byte; 1440 does not.

**Verified here** in J03778134, the filter at `0x18a37a`:

```
0x18a37e  mov   r8d, [r12+0x28]          ; requested height
0x18a388  movzx eax, byte [rax+0x260]    ; the 4K-permission byte
0x18a38f  xor   al, 1
0x18a391  cmp   r8d, 0x870               ; == 2160 ?
0x18a39b  cmovne eax, ecx (0)            ;   not 2160 -> don't care
0x18a3a0  jne   0x18a3c6                 ;   2160 && !perm -> reject-combine
0x18a3ab  cmp   r8d, 0x5a0               ; == 1440 ?
0x18a3b4  cmp   byte [rbp-0x98], 0       ;   isHevc
0x18a3c8  jne   0x18a297                 ; skip (do not advertise)
```

Truth table (identical across all three builds):

| height | needs |
|---:|---|
| 2160 | HEVC **and** permission byte set |
| 1440 | HEVC |
| ≤1080 | always advertised |

Field offset of the permission byte: `config+608` on 12.00; `session+0x260` on 13.60 and
**verified here** (`movzx eax, byte [rax+0x260]`, bytes `0f b6 80 60 02 00 00`).

The 13.60 minimal edit flips only the Neo-derived branch:

```
13.60  SceRemotePlay+0x1A102D   75 09  ->  EB 09
```

Equivalent single-site edit **for J03778134** (forces the permission read to 1, touching
only this site; the field is read nowhere else in this function):

```
J03778134  VA 0x18a388  (file 0x18e388)
  stock        0f b6 80 60 02 00 00     movzx eax, byte [rax+0x260]
  replacement  b8 01 00 00 00 66 90     mov eax, 1 ; nop
```

### Gate A capability source — why it is not client-reachable

The permission byte is set from the console, never from the request. The chain, with the
source being the decisive part:

- **12.00:** `setSupports4K(config,val)` (vtable slot 30, `+0xF0`) is the only writer, and
  every dispatch site passes a hardcoded immediate `0` (`xor esi,esi`). No launchspec key,
  protobuf field, or RPC reaches it. (`RESOLUTION_GATES.md`, "Option 2 resolved".)
- **13.60:** the byte is `sceKernelHasNeoMode -> manager+0x118 -> session+0x260`. A base
  PS5 returns false.
- **Verified here (J03778134):** same shape as 13.60 — the writer is a one-instruction
  setter `0x18b560` (`mov byte [rdi+0x260], sil ; ret`), fed from `manager+0x118`
  (`0x5da2d`), which is set by `0x5ece0` via virtual dispatch with **no direct callers** in
  the image. Consistent with a kernel/entitlement source outside the request path, exactly
  as 13.60 names it.

So the "does any connect field set it" question is **answered, not open**: on 13.60 the
source is `sceKernelHasNeoMode`, a kernel query, not a request field. The corpus build
matches that structure.

### Gate B — AvCapture high-resolution request mode (13.60)

`SceRemotePlay+0x6FBD5` derives the capture request mode as `4 + 2*permission`. Stock
(permission 0) selects mode 4; mode 6 is the high-res path. Edit:

```
13.60  SceRemotePlay+0x6FBD5
  stock        0F B6 83 F0 03 20 00     movzx eax, byte [rbx+0x2003F0]
  replacement  B8 01 00 00 00 66 90     mov eax, 1 ; nop
```

Not re-derived here — this corpus build's mode computation was not isolated to a single
site, and the `+0x2003F0` permission byte is a different field from Gate A's. The resolution
*index* field the handoff warns against patching, `+0x2003E8` (value 6 = 2160), **is present
here** (`0x58456`, `0x58893`), confirming the two builds share this region.

### Gate C — the decisive one: AvCapture high-resolution pools (13.60)

This is why NOPing Gate A alone failed on 12.00 ("a further validator rejects 2160p
downstream") and why the 1440p experiment returned video-init `-5`. It is **not a byte
patch.**

Two memory pools in the **SceAvCapture** process (NPXS40100) are never allocated on a stock
unit, leaving sentinel handles `0x5000000000`; the high-res allocator refuses to proceed
until both are real.

| handle slot | size | align | type |
|---|---:|---:|---:|
| `SceAvCapture+0x2D118` | 16 MiB | 2 MiB | `0xC` |
| `SceAvCapture+0x2D120` | 240 MiB | 2 MiB | `0xB` |

Stock startup (`SceAvCapture+0xA160`) searches only `0x1100000000..0x1200000000` and
ignores allocation failure. The fix follows Sony's newer dynamic pattern:
`sceKernelGetDirectMemorySize` (NID `pO96TwzOm5E`) then
`sceKernelAllocateDirectMemory` (NID `rTXw65xmLIA`) over `0..size`, **in the owning
process**, publishing both handles only after both succeed and releasing on partial success
(`sceKernelReleaseDirectMemory`, NID `MBuItvba6z8`). Mapper: `SceAvCapture+0xA900`; alloc
PLT `+0x1ADF0`; release PLT `+0x1AE50`.

**This offset set is 13.60-specific and cannot be verified here** — NPXS40100 is not in this
corpus. Getting that image dumped is the single most useful next input.

---

## Required chain by resolution

**2560×1440:** HEVC 1440p client entry · Gate B mode-6 edit · both AvCapture pools
initialized · capability tier ≥4. The Neo/2160 branch (Gate A permission, Gate C-adjacent
`+0x260`) is not used for 1440p — which is why 1440p is the cheaper experiment and the
fastest proof the pool work is correct.

**3840×2160 on a base PS5:** HEVC 2160p client entry · Gate B mode-6 edit · both AvCapture
pools initialized · Gate A admission edit · capability tier 5.

Client request shape (13.60): Takion v19, P-521, HEVC, ladder of 1080/1440/2160, 2160 as
starting rung, 30/60 fps, ~40 Mbit/s, `hw5.0` label. The version/label/user-agent are not
themselves gates.

---

## Runtime proof still required

Confirmed only when, in one run: the host selects index 6 / 3840×2160; AvCapture init no
longer returns `-5`; the client receives coded HEVC frames; the decoder reports actual
3840×2160. If it still fails after these gates pass, capture the exact failing return from
AvCapture mapping or encoder init before adding further patches.

## Reversibility

All edits are runtime-only (no modified system module written to storage): restore the
Gate A and Gate B bytes, restore the two AvCapture sentinels and release both allocations in
their owning process, reboot if restoration cannot be verified. A reboot restores process
code and runtime memory.

---

## What this corpus can and cannot settle

- **Settled here:** the Gate A admission filter and its `+0x260` permission byte, the 6-entry
  resolution table, the `+0x2003E8` index field, and that the permission writer has no
  in-image caller — all read out of J03778134 and consistent with the 13.60 handoff.
- **From the handoffs, not re-derived here:** Gate B's single-site mode computation, and all
  of Gate C (different process, image absent).
- **Blocked on a file:** the AvCapture pool work. A decrypted NPXS40100 image would let the
  `+0xA160`/`+0xA900`/`+0x2D118`/`+0x2D120` offsets be checked the same way the RP-host gates
  were.
