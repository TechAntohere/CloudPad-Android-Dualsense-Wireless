# Cloud game eligibility — the real mechanism, and where CloudPad's assumption was wrong

Source: `SceShellCore.elf`, PS5 firmware **13.60**
(`system/vsh/SceShellCore.elf` from the `13.60.zip` dump; this is a `/system/common/lib` +
`/system/priv/lib` + core-daemon dump, not the cloud app itself — see "What is still
missing" below). All addresses are virtual addresses in that image unless stated.

## Bottom line

There is **no per-title on-device "can stream" flag** that CloudPad can read once and
cache. Eligibility is **two separate live checks, run every time**, not one lookup:

1. **Do you hold an entitlement for this title at all** — a per-platform ownership check
   against a server-fetched entitlement record.
2. **Does your account's PS Plus tier currently grant cloud streaming** — a *live* call to
   the Premium/catalog feature API, run unconditionally, every single check, regardless of
   whether (1) already said yes.

Both results are combined into two booleans, `Streamable` and `homeShared`, and *that* pair
— not the raw per-title entitlement — is what the console actually acts on. If CloudPad
has been treating "owns the title" as sufficient, or caching a streamability bit per title,
that is the thing to fix.

---

## Layer 1 — raw per-title entitlement

`cloud_connect_manager_entitlement.cpp :: checkEntitlementForEST` — `0x49f1f0`

```
checkEntitlementForEST(userId, platformStr, titleRecord)
  -> worker                                              0x4878d0
       1. service code from platformStr:
            "PS5" -> "PSGD"
            "PS4" -> "PS4GS"
            else  -> ""                                  (0x1d79ac9, empty string)
       2. composite id = serviceCode + contentId
       3. fetch the entitlement record                    0xefd250
            (AppGetEntitlementByContentIdAndUserId-shaped)
       4. parse the record                                0x4880a0
            looks up JSON key "streamingSupported" (0x488803-0x4888a8):
              absent            -> leave default
              present, not bool -> leave default
              present, bool     -> *out = raw bool byte
       5. write-back (0x487e37-0x487e6a):
            *hasEntitlement = 1          (reaching here means a record was found at all)
            *active         = <parsed>
            *streamingSupported = <parsed, see override below>
  logs: "checkEntitlement() 0x%08x hasEntitlement:%s active:%s streamingSupported:%s"
```

**Platform override**, `0x487c41`-`0x487c6a`, applied right after step 4:

```
if platformStr != "PS5"  (exact 3-byte match):
    streamingSupported = 1        ; forced true
else:
    streamingSupported = <value parsed from the record>
```

Only a check against the literal platform `"PS5"` is actually gated by the server's real
flag. The caller traced below (`0x48d6f0`) invokes this with `platformStr = "PS5"` for its
primary check (`0x48d838`), so the main eligibility path is **not** silently bypassed by
this override — but the override exists, fires for `"PS4"` and anything else, and is worth
knowing about if any other call site passes a different platform string.

---

## Layer 2 — the actual gate: `checkStreamingEntitlement`

This is the one CloudPad needs to match, and it is a **different, higher-level function**
than Layer 1 — not a synonym for it.

```
EntitlementMgr::checkStreamingEntitlement(userId, titleInfo, &outStreamable, &outHomeShared)
    0x48e6a0  — public wrapper, retries once with a 500 ms backoff (0x1959260) if the
                worker reports the cached record needs a refresh (0x48e4f0)
    0x48e860  — the worker:
                  1. look up the user/title record            0x4a0610
                       not found -> "User[%x] not found", bail
                  2. unconditionally call the PS Plus Premium/catalog check:
                       checkPremium(cloudgame, apiVersion) for user
                       -> GET /v2/users/me/premium?feature=cloudgame&useTiers=true
                       (logged as "Check Premium Error" on failure)
                  3. combine with the Layer-1 entitlement result into:
                       outStreamable, outHomeShared
    logs: "userId:%x Streamable:%s homeShared:%s"
```

**The important correction, including one I had to make to my own first read of this
binary:** the two record flags this worker checks (`+0x158`, `+0x170`) are *not* a
"owned outright vs. owned via catalog" branch — both are plain not-loaded guards
(`"UserInfo is not loaded: User[%x]"` on one, generic not-found on the other). The real
finding is simpler than that and matches the catalog behavior exactly: **the Premium/
catalog feature check runs every time, for every title, whether or not Layer 1 already
found a personal entitlement.** `Streamable` is not a stored bit — it is recomputed from a
live server call on every check.

**Related catalog endpoints**, also in this image, that feed the same subsystem:

```
GET /v2/products/cloudgame?country=%s&language=%s&age=99&limit=1000&offset=%d
      &benefitPlanIds=%s&storeFront=PS5&homeshare=%s&allowCronosHomeshare=%s
GET /features/cloudgame?allowCronosHomeshare=false&usePrimaryDeviceAccountIds=true
GET /v2/users/me/premium/features/cloudgame?allowCronosHomeshare=%s&...
local override:  /mnt/usb%d/additional_cloudgame.json
```

`homeshare`/`allowCronosHomeshare` in the catalog query matches the console-sharing
("home share") path reflected in `outHomeShared` — a second, independent reason a title
can be streamable (shared library, not personally owned or catalog-granted).

---

## What this means for CloudPad, concretely

- **Do not cache "streamable" per title.** It is not a static attribute of the title or
  even of the account's catalog membership at a point in time — it is reconstructed from
  a live Premium-tier call on every check. A cached yes can go stale the moment a PS Plus
  tier lapses; a cached no can go stale the moment it's renewed.
- **Checking ownership (Layer 1) alone is not sufficient, and was likely CloudPad's error.**
  A game can return `hasEntitlement = true` and still not be `Streamable` if the Premium
  check fails, and — just as relevant to "owned via catalog" — a game with **no personal
  entitlement at all** can still become `Streamable` purely through the catalog/Premium
  grant, independent of Layer 1. Layer 1 and Layer 2 are both necessary and neither is
  sufficient alone; `Streamable` is their combination, not either one.
- **The "claim" step you described is real in spirit but not yet proven in this
  binary.** `createRequestToConsumeEntitlement()` / `pollAsyncRequestToConsumeEntitlement()`
  exist with real call sites (`0x7ce8b0`, `0xc45890`, `0x7cec80`), but those sites sit in
  generic ShellCore entitlement-consumption code, not inside `cloud_connect_manager`, and
  carry no cloud/stream-identifying string nearby. This document does **not** claim to have
  traced a literal "claim this catalog title's entitlement at cloud-stream-start" call —
  only that the eligibility check itself is re-verified live every time, which is
  consistent with your description but is a weaker, separately-confirmed fact. Closing
  this gap needs the actual cloud session process.

## What is still missing

Everything above is ShellCore's *console-side, pre-launch* eligibility logic — it runs
before `pscloudplayer:play?titleId=<id>` ever fires. The process launched by that URI —
the actual cloud-session client, under `/system_ex/app/NPXS40087/eboot.bin` or
`/system_ex/app/NPXS40099/eboot.bin` — is still not in the corpus. Two independent dumps
pulled during this project (`ps5.7z`-derived, and the separately-shared `ps5_1.7z`) both
contain `libScePSNowGkp.sprx` and `gaikai-player.sprx` for those app IDs but **no
`eboot.bin` for either** — confirmed byte-for-byte identical to what was already on hand,
so this is not an extraction gap on this end, the file genuinely isn't in those dumps.

That eboot is where the literal claim/consume call at stream-init — if it exists as
described — would actually be found.
