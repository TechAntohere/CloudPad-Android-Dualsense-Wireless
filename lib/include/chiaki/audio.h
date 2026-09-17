// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_AUDIO_H
#define CHIAKI_AUDIO_H

#include <stdbool.h>
#include <stdint.h>
#ifndef _WIN32
#include <unistd.h>
#endif

#include "common.h"

#ifdef __cplusplus
extern "C" {
#endif

#define CHIAKI_AUDIO_HEADER_SIZE 0xe

/**
 * Takion audio channel ids, as allocated by the PS5 streaming stack.
 *
 * The console maps a channel *kind* ("main", "voice", "padspk", "haptic") plus a
 * controller index onto a single flat channel id, which is what travels in the
 * AudioChannelPayload.audio_channel_type field and in the per-packet channel byte
 * of a v12 Takion AV audio header:
 *
 *   main          -> 0
 *   voice         -> 1
 *   haptic, pad n -> 2 + n
 *   padspk, pad n -> 6 + n
 *
 * Both "voice" and the four "padspk" channels are only allocated when the host is
 * PS Cloud ("psnow"); retail Remote Play never creates a consumer for them, so
 * asking for them there gets no audio. Everything up to CHIAKI_AUDIO_CHANNEL_COUNT
 * is a valid id; the console itself uses that value as its "no such channel" result.
 */
#define CHIAKI_AUDIO_CHANNEL_MAIN        0
#define CHIAKI_AUDIO_CHANNEL_VOICE       1
#define CHIAKI_AUDIO_CHANNEL_HAPTIC_BASE 2
#define CHIAKI_AUDIO_CHANNEL_PADSPK_BASE 6
#define CHIAKI_AUDIO_CHANNEL_COUNT       10

/** Local controllers a Takion session can carry, and so the number of haptic/padspk channels each. */
#define CHIAKI_AUDIO_CHANNEL_CONTROLLERS_MAX 4

/**
 * Lane formats exactly as the host declares them.
 *
 * padspk: mono, 16 bit, 48 kHz, 480 samples (10 ms) per frame, 960 bytes decoded,
 *         Opus at roughly 48 kbps -- the host declares it with isRawPcm false.
 * haptic: stereo, 16 bit, 3 kHz, 30 samples per frame, 120 bytes, isRawPcm true.
 *         That 120 is why the haptics sink can memcpy int16 samples straight out.
 *
 * So the two per-controller lanes are not alike: haptics arrives as PCM and pad
 * speaker arrives as Opus, and needs decoding before it can be played.
 */
#define CHIAKI_AUDIO_PADSPK_CHANNELS      1
#define CHIAKI_AUDIO_PADSPK_BITS          16
#define CHIAKI_AUDIO_PADSPK_RATE          48000
#define CHIAKI_AUDIO_PADSPK_FRAME_SIZE    480
#define CHIAKI_AUDIO_PADSPK_IS_RAW_PCM    false
#define CHIAKI_AUDIO_PADSPK_MAX_FRAME_SZ  (CHIAKI_AUDIO_PADSPK_FRAME_SIZE * CHIAKI_AUDIO_PADSPK_CHANNELS * 2)

#define CHIAKI_AUDIO_HAPTIC_CHANNELS      2
#define CHIAKI_AUDIO_HAPTIC_BITS          16
#define CHIAKI_AUDIO_HAPTIC_RATE          3000
#define CHIAKI_AUDIO_HAPTIC_FRAME_SIZE    30
#define CHIAKI_AUDIO_HAPTIC_IS_RAW_PCM    true
#define CHIAKI_AUDIO_HAPTIC_MAX_FRAME_SZ  (CHIAKI_AUDIO_HAPTIC_FRAME_SIZE * CHIAKI_AUDIO_HAPTIC_CHANNELS * 2)

static inline bool chiaki_audio_channel_is_haptic(uint8_t channel)
{
	return channel >= CHIAKI_AUDIO_CHANNEL_HAPTIC_BASE
		&& channel < CHIAKI_AUDIO_CHANNEL_HAPTIC_BASE + CHIAKI_AUDIO_CHANNEL_CONTROLLERS_MAX;
}

static inline bool chiaki_audio_channel_is_padspk(uint8_t channel)
{
	return channel >= CHIAKI_AUDIO_CHANNEL_PADSPK_BASE
		&& channel < CHIAKI_AUDIO_CHANNEL_PADSPK_BASE + CHIAKI_AUDIO_CHANNEL_CONTROLLERS_MAX;
}

/** Controller index carried by a haptic or padspk channel id. Undefined for other ids. */
static inline uint8_t chiaki_audio_channel_controller_index(uint8_t channel)
{
	if(chiaki_audio_channel_is_padspk(channel))
		return channel - CHIAKI_AUDIO_CHANNEL_PADSPK_BASE;
	if(chiaki_audio_channel_is_haptic(channel))
		return channel - CHIAKI_AUDIO_CHANNEL_HAPTIC_BASE;
	return 0;
}

static inline uint8_t chiaki_audio_channel_padspk(uint8_t controller_index)
{
	return (uint8_t)(CHIAKI_AUDIO_CHANNEL_PADSPK_BASE + controller_index);
}

/**
 * sceAudioOut port types, as the PS5 client picks them per Takion audio channel.
 *
 * Its openDevice() maps the channel to a local output port and opens it:
 *
 *   channel 1 (voice)        -> port type 2, for the initial user
 *   channels 6-9 (padspk)    -> port type 4, for login user slot (channel - 2) & 3
 *   anything else            -> port type 0
 *
 * The padspk case is selected by a predicate that is literally `channel >= 6 &&
 * channel <= 9`, and the slot index by `(channel - 2) & 3` -- one expression that
 * serves the haptic range too, since it folds 2-5 and 6-9 onto 0-3 alike.
 *
 * Those port handles are what the client then reports back to the host in an
 * AUDIOSTATE PORTSTATES message, which is how the host learns an output for a
 * channel is live. So a pad speaker lane is a port of type 4 being open and
 * reported, not just a channel having been declared.
 */
#define CHIAKI_AUDIO_OUT_PORT_TYPE_MAIN   0
#define CHIAKI_AUDIO_OUT_PORT_TYPE_VOICE  2
#define CHIAKI_AUDIO_OUT_PORT_TYPE_PADSPK 4

static inline uint8_t chiaki_audio_channel_out_port_type(uint8_t channel)
{
	if(channel == CHIAKI_AUDIO_CHANNEL_VOICE)
		return CHIAKI_AUDIO_OUT_PORT_TYPE_VOICE;
	if(chiaki_audio_channel_is_padspk(channel))
		return CHIAKI_AUDIO_OUT_PORT_TYPE_PADSPK;
	return CHIAKI_AUDIO_OUT_PORT_TYPE_MAIN;
}

/**
 * Login-user slot a haptic or padspk channel belongs to, in the console's own single
 * expression. Equivalent to chiaki_audio_channel_controller_index() across both of
 * those ranges; like it, meaningless for any other channel.
 */
static inline uint8_t chiaki_audio_channel_user_slot(uint8_t channel)
{
	return (uint8_t)((channel - CHIAKI_AUDIO_CHANNEL_HAPTIC_BASE) & 3);
}

static inline const char *chiaki_audio_channel_kind_string(uint8_t channel)
{
	if(channel == CHIAKI_AUDIO_CHANNEL_MAIN)
		return "main";
	if(channel == CHIAKI_AUDIO_CHANNEL_VOICE)
		return "voice";
	if(chiaki_audio_channel_is_haptic(channel))
		return "haptic";
	if(chiaki_audio_channel_is_padspk(channel))
		return "padspk";
	return "unknown";
}

typedef struct chiaki_audio_header_t
{
	uint8_t channels;
	uint8_t bits;
	uint32_t rate;
	uint32_t frame_size;
	uint32_t unknown;
} ChiakiAudioHeader;

CHIAKI_EXPORT void chiaki_audio_header_set(ChiakiAudioHeader *audio_header, uint8_t channels, uint8_t bits, uint32_t rate, uint32_t frame_size);
CHIAKI_EXPORT void chiaki_audio_header_load(ChiakiAudioHeader *audio_header, const uint8_t *buf);
CHIAKI_EXPORT void chiaki_audio_header_save(ChiakiAudioHeader *audio_header, uint8_t *buf);

static inline size_t chiaki_audio_header_frame_buf_size(ChiakiAudioHeader *audio_header)
{
	return audio_header->frame_size * audio_header->channels * sizeof(int16_t);
}

/**
 * AUDIOSTATE payload blobs.
 *
 * The client describes its own audio output setup to the host through AUDIOSTATE, and
 * the blob layout is chosen by the state type. On the PS5 client these are filled from
 * sceAudioOut2GetSpeakerInfo, sceAudioOut2GetHrtfIdForCronos,
 * sceAudioOut2GetTvCorrectionInfo and sceAudioOut2GetPortState, and each type is sent
 * only when the values behind it change:
 *
 *   HRTF (3)       4 bytes   the HRTF id
 *   FLAGS (1)      9 bytes   speaker kind byte, then two dwords, the first of which is
 *                            the port availability mask (see the wide-mask feature)
 *   ANGLE (2)     64 bytes   16 speaker positions, two uint16 each
 *   FULL (0)      73 bytes   the FLAGS 9 bytes followed by the ANGLE 64
 *   TVCONFIG (4) 188 bytes   a dword, two 90-byte correction blocks, then 2 + 2 bytes
 *   PORTSTATES (5) 33 bytes  group index byte, then that group's 32-byte port block
 *
 * PORTSTATES is the interesting one for the pad speaker: the client opens local output
 * ports per audio channel type and reports them here, in groups of four, which is how
 * the host learns an output for a channel is actually live.
 */
#define CHIAKI_AUDIO_STATE_HRTF_SIZE        4
#define CHIAKI_AUDIO_STATE_FLAGS_SIZE       9
#define CHIAKI_AUDIO_STATE_ANGLE_SIZE       64
#define CHIAKI_AUDIO_STATE_FULL_SIZE        (CHIAKI_AUDIO_STATE_FLAGS_SIZE + CHIAKI_AUDIO_STATE_ANGLE_SIZE)
#define CHIAKI_AUDIO_STATE_TVCONFIG_SIZE    188
#define CHIAKI_AUDIO_STATE_PORTSTATES_SIZE  33
/** Largest of the above, and the size the client allocates for every blob. */
#define CHIAKI_AUDIO_STATE_MAX_SIZE         CHIAKI_AUDIO_STATE_TVCONFIG_SIZE

#define CHIAKI_AUDIO_STATE_PORTS_PER_GROUP  4
#define CHIAKI_AUDIO_STATE_PORT_GROUPS      2
#define CHIAKI_AUDIO_STATE_SPEAKER_ANGLES   16

/**
 * One port's state within a PORTSTATES group.
 *
 * The client fills these from the port-state call in libSceAudioOut -- the v1 library,
 * not libSceAudioOut2, so this is sceAudioOutGetPortState and its SceAudioOutPortState:
 *
 *   +0x00  uint16  output          output destination mask
 *   +0x02  uint8   channel
 *   +0x03  uint8   reserved
 *   +0x04  int16   volume
 *   +0x06  uint16  rerouteCounter
 *   +0x08  uint64  flag
 *   +0x10  uint64  reserved[2]                                    -- 32 bytes total
 *
 * Only three are copied out: output, volume, and the low half of flag. The wire block
 * is a field-major transpose of those across a group's four ports, so this struct is
 * also the wire layout.
 *
 * The call is confirmed, not guessed: its NID, GrQ9s4IrNaQ, resolves to
 * sceAudioOutGetPortState in a libSceAudioOut symbol table. That agrees with where the
 * import sits (library libSceAudioOut, beside sceAudioOutOpen, rather than
 * libSceAudioOut2), and the three offsets read plus the 32-byte stack slot the caller
 * gives it match SceAudioOutPortState exactly.
 */
typedef struct chiaki_audio_state_port_t
{
	/** SceAudioOutPortState.output, +0x00: where this port's audio is being sent. */
	uint16_t output;
	/** SceAudioOutPortState.volume, +0x04. Signed in the SDK struct; carried as-is. */
	uint16_t volume;
	/** Low 32 bits of SceAudioOutPortState.flag, +0x08. */
	uint32_t flag;
} ChiakiAudioStatePort;

/** One speaker position in an ANGLE or FULL blob. */
typedef struct chiaki_audio_state_angle_t
{
	uint16_t a;
	uint16_t b;
} ChiakiAudioStateAngle;

/**
 * Build an AUDIOSTATE blob. Each returns the number of bytes written, or 0 if the
 * buffer is too small for that type.
 */
CHIAKI_EXPORT size_t chiaki_audio_state_build_hrtf(uint8_t *buf, size_t buf_size, uint32_t hrtf_id);
CHIAKI_EXPORT size_t chiaki_audio_state_build_flags(uint8_t *buf, size_t buf_size,
		uint8_t speaker_kind, uint32_t available_bits, uint32_t dword_2);
CHIAKI_EXPORT size_t chiaki_audio_state_build_angle(uint8_t *buf, size_t buf_size,
		const ChiakiAudioStateAngle *angles);
CHIAKI_EXPORT size_t chiaki_audio_state_build_full(uint8_t *buf, size_t buf_size,
		uint8_t speaker_kind, uint32_t available_bits, uint32_t dword_2,
		const ChiakiAudioStateAngle *angles);
CHIAKI_EXPORT size_t chiaki_audio_state_build_port_states(uint8_t *buf, size_t buf_size,
		uint8_t group, const ChiakiAudioStatePort *ports);

#ifdef __cplusplus
}
#endif

#endif // CHIAKI_AUDIO_H
