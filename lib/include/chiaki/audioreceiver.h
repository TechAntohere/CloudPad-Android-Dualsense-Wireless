// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_AUDIORECEIVER_H
#define CHIAKI_AUDIORECEIVER_H

#include "common.h"
#include "log.h"
#include "audio.h"
#include "takion.h"
#include "thread.h"
#include "packetstats.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef void (*ChiakiAudioSinkHeader)(ChiakiAudioHeader *header, void *user);
typedef void (*ChiakiAudioSinkFrame)(uint8_t *buf, size_t buf_size, void *user);

/**
 * Frame of one controller's pad speaker lane. Unlike the main and haptics sinks this
 * carries the controller index, because the host runs one padspk channel per pad.
 */
typedef void (*ChiakiPadSpeakerSinkFrame)(uint8_t controller_index, uint8_t *buf, size_t buf_size, void *user);

/**
 * Sink that receives Audio encoded as Opus
 */
typedef struct chiaki_audio_sink_t
{
	void *user;
	ChiakiAudioSinkHeader header_cb;
	ChiakiAudioSinkFrame frame_cb;
} ChiakiAudioSink;

/**
 * Sink that receives one controller's pad speaker lane, tagged with a controller index.
 *
 * Frames are Opus (mono, 48 kHz, 480 samples per frame) and still need decoding --
 * the host declares this lane with isRawPcm false, unlike the haptics lane, which is
 * raw PCM and reaches its sink ready to use.
 */
typedef struct chiaki_pad_speaker_sink_t
{
	void *user;
	ChiakiAudioSinkHeader header_cb;
	ChiakiPadSpeakerSinkFrame frame_cb;
} ChiakiPadSpeakerSink;

typedef struct chiaki_audio_receiver_t
{
	struct chiaki_session_t *session;
	ChiakiLog *log;
	ChiakiMutex mutex;
	ChiakiSeqNum16 frame_index_prev;
	bool frame_index_startup; // whether frame_index_prev has definitely not wrapped yet
	ChiakiPacketStats *packet_stats;
	void *pscloud_audio_reassembler; // Opaque pointer to ChiakiPSCLOUDAudioReassembler (PSCLOUD only)
} ChiakiAudioReceiver;

CHIAKI_EXPORT ChiakiErrorCode chiaki_audio_receiver_init(ChiakiAudioReceiver *audio_receiver, struct chiaki_session_t *session, ChiakiPacketStats *packet_stats);
CHIAKI_EXPORT void chiaki_audio_receiver_fini(ChiakiAudioReceiver *audio_receiver);
CHIAKI_EXPORT void chiaki_audio_receiver_stream_info(ChiakiAudioReceiver *audio_receiver, ChiakiAudioHeader *audio_header);
CHIAKI_EXPORT void chiaki_audio_receiver_av_packet(ChiakiAudioReceiver *audio_receiver, ChiakiTakionAVPacket *packet);

static inline ChiakiAudioReceiver *chiaki_audio_receiver_new(struct chiaki_session_t *session, ChiakiPacketStats *packet_stats)
{
	ChiakiAudioReceiver *audio_receiver = CHIAKI_NEW(ChiakiAudioReceiver);
	if(!audio_receiver)
		return NULL;
	ChiakiErrorCode err = chiaki_audio_receiver_init(audio_receiver, session, packet_stats);
	if(err != CHIAKI_ERR_SUCCESS)
	{
		free(audio_receiver);
		return NULL;
	}
	return audio_receiver;
}

static inline void chiaki_audio_receiver_free(ChiakiAudioReceiver *audio_receiver)
{
	if(!audio_receiver)
		return;
	chiaki_audio_receiver_fini(audio_receiver);
	free(audio_receiver);
}

#ifdef __cplusplus
}
#endif

#endif // CHIAKI_AUDIORECEIVER_H
