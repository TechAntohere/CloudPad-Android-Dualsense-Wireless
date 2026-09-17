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

/** Pad speaker lane format: mono signed 16 bit 48 kHz, 480 samples (10 ms) per frame. */
#define CHIAKI_AUDIO_PADSPK_CHANNELS    1
#define CHIAKI_AUDIO_PADSPK_BITS        16
#define CHIAKI_AUDIO_PADSPK_RATE        48000
#define CHIAKI_AUDIO_PADSPK_FRAME_SIZE  480

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

#ifdef __cplusplus
}
#endif

#endif // CHIAKI_AUDIO_H
