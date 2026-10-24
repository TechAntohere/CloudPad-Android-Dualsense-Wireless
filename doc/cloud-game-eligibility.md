# Cloud game eligibility — the real mechanism, confirmed against CloudPad's own code

Source for the console side: `SceShellCore.elf`, PS5 firmware **13.60**
(`system/vsh/SceShellCore.elf` from the `13.60.zip` dump; this is a `/system/common/lib` +
`/system/priv/lib` + core-daemon dump, not the cloud app itself — see "What is still
missing" below). All addresses are virtual addresses in that image unless stated.

Source for the client side: this repository, `android/app/src/main/java/com/metallic/chiaki/cloudplay/`.

## Correction to an earlier chat answer, stated plainly

A first pass through only the firmware (no code read) led to writing here that CloudPad
was probably caching a "streamable" bit or treating raw ownership as sufficient, and that
this was "the thing to fix." Having now actually read `PsCloudOwnership.kt`,
`PsCloudCatalogService.kt`, `CloudGameRepository.kt`, and the Gaikai session code, **that
was wrong.** CloudPad already implements the real two-layer mechanism correctly, already
treats streamability as unverified by default, and already never gates a launch attempt
on a cached guess. The sections below show exactly where, file and line. That earlier
framing is replaced by what follows.

## Bottom line

There is **no per-title on-device "can stream" flag**, on the console or in CloudPad.
Eligibility is **two separate checks**:

1. **Do you hold an entitlement for this title at all** — a per-platform ownership check
   against a server-fetched entitlement record.
2. **Does your account's PS Plus tier currently grant cloud streaming** — a check against
   the Premium/catalog feature, which the console re-runs live on every check regardless
   of whether (1) already said yes, and which CloudPad's own session-allocation flow
   re-triggers live on every stream attempt for the same reason (see "Layer 2 — CloudPad's
   equivalent" below).

Both results combine into what the console calls `Streamable`/`homeShared`, and CloudPad's
own `StreamableStatus` enum is the same idea, already built the same way: optimistic from a
catalog match, never a hard gate, only ever confirmed by a real attempt.

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

## Verified mapping — firmware mechanism to CloudPad code

### Layer 1 (ownership) — CloudPad's equivalent

CloudPad doesn't go through ShellCore; it hits Sony's REST entitlement APIs directly. But
the fields it reads are the **same server fields** the firmware parser pulls out of the
entitlement record:

| firmware (ShellCore) | CloudPad |
|---|---|
| JSON key `"streamingSupported"` (`0x488803`) | `PsCloudCatalogService.kt:164,226`: `gameObj.optBoolean("streamingSupported", false)` — the literal field name, read off the Imagic catalog list |
| JSON key `"activeFlag"` (`0x488184`) | `PsCloudOwnership.kt:73`: `Entitlement.activeFlag = obj.optBoolean("active_flag", false)` |
| `featureType` branch on the entitlement record | `PsCloudOwnership.kt:23,77`: `featureType: Int // 3=full game, 1=trial/free, 0=add-on/DLC`, read from `feature_type` |
| per-platform service code ("PSGD"/"PS4GS") selecting which record to fetch | `PsCloudOwnership.platformToken()`: PPSA → ps5, CUSA → ps4 — the same PS5/PS4 split, used to pick the right entitlement id to stream |

Independent arrival at the same field names from two different sources — a native JSON
parser inside the console binary, and someone else's reverse-engineered REST client — is
about as good a cross-check as this project gets without a live capture.

### Layer 2 (the live gate) — CloudPad's equivalent, and where the "claim" actually is

This is the part worth being precise about, because it answers the original question
directly: **CloudPad already has a claim-at-stream-start step, and it is Gaikai's own
session-authorize call — not a separate purchase/consume HTTP request.**

```
PSGaikaiStreaming.kt :: startAllocationFlow()
  step8_StartSession()       POST /v1/sessions/start            — sends entitlementId
  step9_AuthorizeSession()   POST /v1/sessions/{id}/authorize    — THE LIVE GATE
       on failure: reads eventCode from the X-Gaikai-Event header / errors[].eventCode
       eventCode == "002.2001" -> throw PsPlusSubscriptionException
       otherwise              -> throw GaikaiAllocationException
```

`step9_AuthorizeSession` runs **every time**, for every stream attempt, `pscloud` and
`psnow` alike, with no catalog/ownership-based skip — matching the firmware's Layer 2
exactly (`checkPremium(cloudgame, ...)` runs unconditionally, never gated by Layer 1's
result). `eventCode 002.2001` being a named, hardcoded constant in this codebase means
this failure mode was observed for real at some point — i.e. this live check, and its
specific rejection code, is empirically confirmed to exist server-side, not just inferred
from firmware. That *is* the "claimed at cloud stream initialization" mechanism: the claim
isn't a distinct call, it's that Gaikai's own `/authorize` endpoint performs the live
entitlement + Premium-tier check at the moment a session is actually requested, and will
reject a session that the catalog or ownership check alone wouldn't have predicted either
way.

The older `PSKamajiSession.kt` path (PSNow commerce flow) has a *separate*, explicit
claim call — `step0_5e3_CheckoutPreview` / `step0_5e4_CheckoutBuynow`, a zero-price
checkout that acquires an individually-purchasable entitlement if one doesn't exist yet.
It is correctly **skipped** for catalog-granted titles
(`preKnownEntitlementId`/`fastPathEntitlementId`, with the comment: *"a 404 from commerce
means the game is included via PS Plus Premium subscription... skip checkout and proceed
directly to streaming"*) — there is nothing to purchase for a subscription grant, and the
real gate for those titles is the same Gaikai `/authorize` call every other path goes
through regardless.

### Streamability as a UI hint, never a gate — already correct

`StreamableStatus` (`CloudGame.kt`) is `STREAMABLE | NOT_STREAMABLE | UNKNOWN`, and the
code comment on it already states the firmware's finding independently: *"STREAMABLE...
when Sony's public catalog confirms streamingSupported=true... NOT_STREAMABLE [only] from
a real launch attempt, which always wins over the catalog guess."* Confirmed by reading
the call graph: `CloudPlayFragment`/`CloudStreamingBackend` invoke
`PSGaikaiStreaming.startAllocationFlow()` unconditionally on tap — nothing checks
`streamableStatus` before attempting a stream. The only writer of `NOT_STREAMABLE` is
`setConfirmedStreamable()` after a real attempt's outcome. This is exactly "don't cache it
as a gate," already built that way.

One field worth flagging as inert rather than broken: `CloudGame.plusCatalog` is populated
and unit-tested (`PsCloudOwnershipTest.kt`) but doesn't change any streaming call — which
is correct, since Layer 2 is unconditional either way, but worth knowing if it's ever
mistaken for a dead field to delete.

## What is still missing

The firmware-side trace (`checkEntitlementForEST` / `checkStreamingEntitlement`) is
ShellCore's *console-side, pre-launch* eligibility logic — it runs before
`pscloudplayer:play?titleId=<id>` ever fires, on the console's own catalog/library UI. The
process launched by that URI — the actual cloud-session client, under
`/system_ex/app/NPXS40087/eboot.bin` or `/system_ex/app/NPXS40099/eboot.bin` — is still not
in the corpus. Two independent dumps pulled during this project (`ps5.7z`-derived, and the
separately-shared `ps5_1.7z`) both contain `libScePSNowGkp.sprx` and `gaikai-player.sprx`
for those app IDs but **no `eboot.bin` for either** — confirmed byte-for-byte identical to
what was already on hand, so this is not an extraction gap on this end, the file genuinely
isn't in those dumps.

That eboot would show the console's own equivalent of `step9_AuthorizeSession` from the
inside, and would settle the one remaining loose end: the generic ShellCore
`createRequestToConsumeEntitlement()` call sites (`0x7ce8b0`, `0xc45890`, `0x7cec80`) found
earlier still aren't proven to be *this* mechanism rather than ordinary purchase/addon
consumption — but with CloudPad's own Gaikai `/authorize` behavior now confirmed as the
real, working claim-at-stream-start step, that ShellCore detail is no longer load-bearing
for understanding the mechanism, only for a complete console-side picture.
