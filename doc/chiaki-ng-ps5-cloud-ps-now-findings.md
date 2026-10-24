# chiaki-ng findings — PS5 cloud, PS Now and local Remote Play

Summarized from the chiaki-ng team's research knowledgebase (`verified/` and `unverfied/`,
updated 2026-10-22). Source files are named per section so the original can be checked. None of
this came from kernel/hypervisor access — it is protocol and client-firmware reverse engineering
of retail builds (NPXS40099/40102/40106, PS5 ShellCore, Sony's Mac Remote Play app), the same
kind of material already in this repo's other `doc/*.md` files.

## PS Now — PS button and system menu

Source: `PS_NOW_PS_BUTTON_AND_SYSTEM_MENU.md`. Confirmed live.

- PS Now's server ignores the PS bit in controller state entirely.
- **PS3 titles**: a real server-side XMB exists. Toggle it with Takion `XMBCOMMAND` (type 15),
  `TOGGLE` + the pressing pad's `player_id`, sent on the PS-button-down edge.
- **PS4 titles**: no server-side menu. Sony's own clients show a local 4-item menu (Return to
  game / Quit / Application Saved Data Management / Vibration) instead of forwarding PS at all.
  "Application Saved Data Management" is a typed Takion DIRECTMESSAGE deep link, type `SAVE_DATA`
  (see `PS_NOW_SAVED_DATA_DEEP_LINK.md` in the same corpus).
- A client that just forwards the PS bit, as it would for a PS5 cloud/console session, gets
  nothing on PS Now.

**For CloudPad**: if PS Now titles are supported, this needs its own PS-button path per title
generation — PS3 sends `XMBCOMMAND TOGGLE`, PS4 needs a local stream-menu entry instead of
forwarding PS.

## Cloud session health and timeouts

Source: `CLOUD_SESSION_HEALTH_AND_TIMEOUTS.md`. Confirmed live.

- Sony's clients end a cloud stream after **20 seconds with no inbound packet of any kind** (AV,
  control, heartbeat) and do not reconnect. Confirmed: a cloud server that stopped sending video
  caused the stream to end with "the peer stopped sending data" ~20s later.
- Other Sony timeouts, read from the client but not yet confirmed live: 60s AV-specific timeout,
  a 5s-then-20s low-quality countdown, a 10-minute-then-60s AFK countdown (AFK values arrive in
  STREAMINFO as `afkTimeout`/`afkTimeoutDisconnect`).
- This 20s rule is cloud-only in chiaki-ng; console sessions leave the equivalent timer disabled
  (value 0).

**For CloudPad**: a straightforward, well-specified addition — track last-inbound-packet time
during a cloud session, fail with a clear "peer stopped sending data" reason at 20s of total
silence. Low implementation risk, confirmed behavior.

## PS4/PS5 version grouping in the PS Cloud library

Source: `PS_CLOUD_CROSS_GEN_LIBRARY_GROUPING.md`. Confirmed working, read from live PSN data.

- Cross-gen titles can be paired **without an extra request**: a PS5 product's SKUs list the PS4
  entitlement of the same game alongside the PS5 one, in category data the library already
  fetches.
- Pairing rule: for each PS5 title in the Premium/for-sale categories, walk its SKUs' entitlement
  title numbers; any PS4 title found there links to that PS5 title. First link wins (keeps Sony's
  product ordering as tiebreak). Bundles are never paired.
- Separately, game-invitation notifications (`NUC3`) carry `associatedTitles` — every PS4 and PS5
  title id of one game's concept — so an invitation sent from either generation can be matched to
  whichever version a client streams, without a store lookup.

**For CloudPad**: this is a pure client-side grouping/display improvement over data CloudPad's
catalog service already has (`PsCloudCatalogService.kt`/`PsCloudOwnership.kt` from earlier this
session already pull category and entitlement data) — no new network surface, just a linking
pass over existing responses.

## PS5 surround, voice and microphone audio

Source: `PS5_SURROUND_AND_MICROPHONE_AUDIO.md`. Confirmed measured, both local and cloud.

- **No real surround over PS5 Remote Play or PS5 cloud, confirmed.** A 5.1/7.1/7.1.4 stream
  negotiates and transports fine, but every channel past front L/R carries exact zeros — the host
  always captures stereo and pads it. Measured on a retail console and on cloud. Keep stereo for
  speakers; use 3D audio (binaural) for headphones instead (see the local-3D-audio section below).
- Launch combinations that **crash the PS5 host**, confirmed: `fecMode 0` + 7.1 selected (any
  profile count); `fecMode 2` + 7.1 + an explicit 416kbps bitrate. Safe: `fecMode 1` with
  surround, or `fecMode 2` with 64kbps on every profile. Never send `fecMode 0` with surround.
- Audio bitrate ceiling: with `fecMode 1`, each 10ms frame becomes 3x data (1 source + 2 parity
  units), and the PS5 only delivers ~800kbps of audio regardless. 7.1 at 256kbps = all packets
  received; at 416kbps, only ~64% arrive.
- **Microphone packing, confirmed across every Sony client** (PS5 local, PS5 cloud, PS Now,
  Portal): Opus mono 48kHz/480-sample frames, packed 3-per-packet as `[newest, n-2, n-1]`, no FEC.
  A live test with this ordering gave clearer voice than the older `[n, n-1, n]` layout.
- Pad-speaker/voice lane additions: `platform: prospero` is the switch that gets separate speaker
  lanes (confirmed — this is the identity CloudPad's Gaikai path already experiments with, see
  `PSGaikaiStreaming.kt`'s `CLOUD_IDENTITY_MODEL`/`PLATFORM` constants found earlier this
  session). Each pad-speaker packet is 3x 60-byte mono Opus units, same `[newest, n-2, n-1]`
  order as mic — decode unit 0 only. Sending PORTSTATES group 0 (pad speaker, vol 100) + group 1
  (main, vol 100) at stream-ready fixes a ~55s all-audio-to-speaker-lane bug seen without it.
- **macOS Bluetooth DualSense speaker confirmed unusable at the OS level**: needs ~36.5KB/s, the
  Classic Bluetooth link only carried ~5KB/s at normal 15ms sniff / ~10KB/s in Game Mode (7.5ms),
  regardless of report size or sync/async writes. This matches and independently confirms the
  measurements discussed earlier in this session (Street pea's write-speed table). USB works.

**For CloudPad**: the crash-avoidance table (never `fecMode 0` + surround) and the confirmed mic
packing order are both directly checkable against CloudPad's own audio code. The macOS Bluetooth
ceiling confirms this isn't fixable client-side on that platform short of bypassing the OS stack
(per the L2CAP tutorial already produced this session).

## PS5 cloud HDR

Source: `PS5_CLOUD_HDR.md`. Confirmed working above 1080p only.

- Sony's rule (from NPXS40106 `setVideoSettings`): `codec = maxRes >= 1081 ? hevc : avc`,
  `dynamicRange = codec == hevc ? hdr : sdr`. **1080p cloud requests are always SDR**, regardless
  of what's requested — HDR only exists at 1440p/4K.
- Working request: `supportedDynamicRange`/`preferredDynamicRange` "HDR", top-level `videoCodec`
  hevc, `dynamicRange` HDR, cloud resolution 2160, **kept the PS Portal identity** (not PS5).
  Verified real HDR (not just tagged) by counting distinct luma levels (~557/frame, matching a
  local PS5 HDR reference) rather than trusting the pixel format alone.
- Bandwidth: 35Mbps claimed → 1440p HEVC HDR; 50Mbps claimed → full 4K HEVC HDR. The server picks
  the step from `network.bwKbpsSent`/`bwKbpsReceived`, which Sony's client fills from its own
  Senkusha bandwidth test (see below).

**For CloudPad**: directly actionable and ties into this session's earlier 4K/resolution-gate
work — confirms 4K HDR cloud streaming works today with nothing more than requesting 2160 +
HEVC + HDR + a claimed bandwidth above 35Mbps, no firmware patching needed (unlike the local
console 4K gate work from earlier in this session, which does need one).

## PS5 cloud system UI, accessibility and Cloud Messaging

Source: `PS5_CLOUD_SYSTEM_UI_AND_MESSAGING.md`. Confirmed on real cloud sessions.

- Background/foreground state: `psaction:systemservicestatus?isInBackgroundExecution=0|1` and
  `?isSystemUiOverlaid=0|1`, sent as raw deep links (Takion DIRECTMESSAGE → DEEPLINK → type RAW).
  `isInBackgroundExecution=1` acts like a PS-button press (home menu replaces the game).
  chiaki-ng's rule: send it on screen lock/minimize *unless* "Background input" is on.
- Live accessibility: `psaction:accessibility?tts=…&ttsspeed=…&ttsvolume=…&textsize=0|1&…` updates
  the cloud PS5's own menus mid-stream, no restart needed.
- **Cloud Messaging requires one easy-to-miss detail**: every DIRECTMESSAGE `data` field
  (deep links included) is XORed with the sender's keystream at the carrying control packet's key
  position **+16**. Without this, Cloud Messaging silently doesn't work.
- Cloud Messaging addresses observed: 4 (handshake), 6 (game intents the service couldn't start),
  7 (ServiceFeedback toasts, e.g. screenshot/clip saved), 8 (persistent banners), 11
  (prenoticeUpload), 12 (Create-button presses, client→server), 13 (capture results), 14 (deep
  links on the cloud side, filtered by ShellCore's `checkWhiteList` allow-list).

**For CloudPad**: the background-state deep links are small, well-specified, and useful (stops
the cloud console idling the game when the app is backgrounded). Cloud Messaging is a bigger
lift — worth it only if CloudPad wants Create-button capture/clips.

## PS5 cloud trials and Play First Trials

Source: `PS5_CLOUD_TRIALS.md`. Confirmed on a live Play First Trial run.

- **A used-up Play First Trial entitlement is deleted entirely** — there's no flag that says "you
  used this." The public store keeps listing it regardless.
- The *only* reliable used/unused signal: the signed-in store's call-to-action list offers
  `DOWNLOAD_PS_PLUS_TRIAL` only while the trial is still unused. Don't rely on
  `isPlayTimeFullyConsumed` (mobile API never surfaces it) or any device-clock-based expiry.
- `featureType` meanings confirmed: 1=TRIAL (mostly demos), 3=FULL_GAME, 5=DISC_REQUIRED,
  7=PLAY_FIRST_TRIAL.

**For CloudPad**: relevant if the catalog ever needs to show "trial already used" — the rule is
narrow and specific (check the CTA list, cache the result per-account, never expire by age).

## Senkusha bandwidth test

Source: `SENKUSHA_BANDWIDTH_TEST.md`. Confirmed on PS5 cloud; explicitly avoid against a console.

- Full algorithm, defaults and step-down loop are documented (preliminary test at 1000kbps, main
  test at 15000kbps or 45000kbps for 2160 targets, loss-based step-down with a 1000kbps floor
  drop per iteration). This feeds `network.bwKbpsSent`/`bwLoss`/`mtu` in the launch request,
  which the cloud server uses to pick the resolution step (ties directly to the HDR section
  above — claim >35Mbps for 4K HDR).
- **Against a real PS5 console, a 50Mbps/3s bandwidth test crashed Remote Play**, and ~8s of
  calibration made the console close the ctrl connection before the stream even started. Sony's
  own Mac app does not run a bandwidth test against a console — only RTT/MTU. Confirmed: test
  bandwidth for cloud only.

**For CloudPad**: the "never run Senkusha bandwidth test against a console" rule is a real,
confirmed crash risk worth checking CloudPad doesn't already do accidentally — console sessions
should stay RTT/MTU-only.

## DualSense trigger state and console motion settings

Source: `DUALSENSE_TRIGGER_STATE_AND_MOTION_SETTINGS.md`. Confirmed on hardware.

- **Adaptive trigger downgrade fix, confirmed**: a local PS5 silently downgrades advanced trigger
  modes (e.g. Bow, `0x22`) to off (`0x05`) unless the ctrl request carries header
  `RP-PadProcNo: 3`. With it, the real mode comes through. PS5 cloud already sends the real mode
  without this header. A long list of other things were tried and ruled out (feature-report byte
  changes, `RP-ControllerType`, Takion v18, `connectedControllers` hints) — only the header fixes
  it. One header value (`RP-SupportCmd: 140000`) actively breaks the session (403) because it
  advertises a newer handshake than intended.
- Controller feedback state v12+ byte layout for reporting trigger effect state back: `0x19`=L2
  state, `0x1a`=R2 state, `0x1b` bit0=emulate flag (for non-DualSense pads).
- **Console motion settings, confirmed by byte offset**: pad info carries reset-orientation,
  tilt-correction, and an angular-velocity dead band (~1°/s threshold). Byte offsets differ by pad
  info layout version (12/13/14 in the 0x19 layout; 4/5/6 in the 0x11 layout). An older
  third-party implementation misread these by reading the player-index/LED bytes instead.

**For CloudPad**: both are concrete, narrow fixes worth checking against CloudPad's own
DualSense/controller code — the `RP-PadProcNo: 3` header especially, since it's a one-line ctrl
header addition with a clear, confirmed symptom (adaptive triggers silently going flat on some
games) and a clear fix.

## Local 3D audio (Tempest) over Remote Play

Source: `PS5_LOCAL_3D_AUDIO_REMOTE_PLAY.md`. Confirmed working and audible.

- Local/internet PS5 sessions use a **different control than cloud** for 3D audio: the launch
  spec must advertise all five audio profiles (`[2,6,8,12,2]` channels — stereo, 5.1, 7.1, 7.1.4,
  3D-audio-binaural-stereo), then AUDIOSTATE `CHANNELNUM` selects profile 4 live. Cloud instead
  uses AUDIOSTATE FLAGS bit 0. PS4 and PS Now have no 3D audio path at all.
- A spec listing only the wanted profile — what earlier third-party clients did — gives the
  console nothing to switch to; this is the most actionable single finding here.
- A full five-profile spec's base64 is ~2KB, too big for one BIG-message packet; it must be
  chunked (CloudPad's underlying `stream_connection_send_big`/reliable channel already does this
  per the note).
- `CHANNELNUM` needs Takion feature 15 (v18+). Confirmed live: positional audio audible front and
  back, not just left/right; ~1-1.5dB level drop at each switch into profile 4, five-for-five.

**For CloudPad**: directly implementable — advertise all five profiles in the local/internet
launch spec (not just whichever one is selected), then send `CHANNELNUM` on toggle. Exact working
JSON block is in the source doc.

## PS5 automatic HDR over local Remote Play

Source: `PS5_LOCAL_AUTO_HDR_REMOTE_PLAY.md`. Confirmed via captured frame statistics.

- **Request `"dynamicRange":"AUTO"`, not `"HDR"`, for local sessions.** Forcing `"HDR"` while the
  TV is off/non-HDR returns untagged 8-bit SDR (same picture as requesting SDR, just untagged).
  `"AUTO"` always returns tagged 10-bit HDR10: SDR-equivalent brightness with noticeably smoother
  gradients (534-588 distinct levels vs. SDR's ~220-250) when the display isn't HDR, and full HDR
  brightness (~1000 nit peak) when it is.
- This is local-session-only; cloud launch requests are unaffected by this finding.
- The rest of the doc (extended-range layer threading, tone-mapping curve, MetalFX clamp) is
  macOS/Metal rendering-pipeline specific and doesn't apply to CloudPad's Android path.

**For CloudPad**: the one-line, platform-independent part — request `AUTO` not `HDR` for local
sessions — is cheap and directly portable. The display-pipeline tone-mapping work is macOS-only
and not applicable.

## PS5 cloud invitations and game intents — still unverified

Source: `unverfied/PS5_CLOUD_INVITATIONS_AND_GAME_INTENTS.md`. **Verdict: unconfirmed — no
third-party join into a PS5 cloud session has worked yet.** I checked `streetpea/chiaki-ng`'s
public commit history for related work (`git log --grep` across invitation/intent/notification/
join-session terms, and by path) and found nothing implementing this — the closest unrelated hit
was a holepunch notification-handling fix, not game invitations. This tracks with the doc's own
conclusion: it's an open research problem, not a merged feature.

Key findings, for context:

- Invitations are read from the same PSN notification list CloudPad already has reason to use for
  the join-notification work discussed earlier this session: `GET
  https://m.np.playstation.com/api/userNotificationManager/v1/users/me/notifications`, use case
  `NUC3`, fields `invitationId`/`sessionId`/`associatedTitle(s)`.
- The typed `INVITE` deep link (Takion DIRECTMESSAGE → DEEPLINK → type `INVITE`) is accepted by
  both PS5 cloud and PS Now servers (`result 0`), but **doesn't actually join the game** in either
  case tested. On PS Now it gets as far as "cannot join the streaming game's game session"; a
  real PS5 cloud client was traced and found to never even send the typed form — it only forwards
  opaque `RAW` text deep links sourced from the inviting PS5's own shell, whose format isn't in
  any firmware dump inspected so far.
- Multiple other candidate join formats were found in strings/sources (`pssession:join?...`,
  a `sendGameIntent` JSON body, `pscloudplayer:play?...`) but none has been shown to actually
  work from a third-party client; the `sendGameIntent` route returns 401/403 with the tokens a
  third-party client can hold.

**For CloudPad**: not implementable yet, same conclusion as the PSN-identity/seat-binding gap
found earlier this session — both require either a live capture of Sony's real server-side
transaction or the still-missing ShellUI JavaScript that builds the real `psaction:invitation`
link. Worth revisiting if either surfaces.
