// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_TAKION_H
#define CHIAKI_TAKION_H

#include "common.h"
#include "thread.h"
#include "log.h"
#include "gkcrypt.h"
#include "seqnum.h"
#include "stoppipe.h"
#include "reorderqueue.h"
#include "feedback.h"
#include "takionsendbuffer.h"

#include <stdbool.h>

#ifdef _WIN32
#include <winsock2.h>
#endif


#ifdef __cplusplus
extern "C" {
#endif

// NOTE: Takion protocol selection is derived from ChiakiServiceType; there is no separate protocol enum.

typedef enum chiaki_takion_message_data_type_t {
	CHIAKI_TAKION_MESSAGE_DATA_TYPE_PROTOBUF = 0,
	CHIAKI_TAKION_MESSAGE_DATA_TYPE_RUMBLE = 7,
	CHIAKI_TAKION_MESSAGE_DATA_TYPE_PAD_INFO = 9,
	CHIAKI_TAKION_MESSAGE_DATA_TYPE_TRIGGER_EFFECTS = 11,
} ChiakiTakionMessageDataType;

typedef struct chiaki_takion_av_packet_t
{
	ChiakiSeqNum16 packet_index;
	ChiakiSeqNum16 frame_index;
	bool uses_nalu_info_structs;
	bool is_video;
	bool is_haptics;
	/**
	 * Takion audio channel id this packet belongs to (CHIAKI_AUDIO_CHANNEL_*).
	 * Only meaningful for audio packets on protocol v12; older protocols carry no
	 * channel byte and are always treated as the main channel.
	 */
	uint8_t audio_channel;
	ChiakiSeqNum16 unit_index;
	uint16_t units_in_frame_total; // source + units_in_frame_fec
	uint16_t units_in_frame_fec;
	uint8_t codec;
	uint16_t word_at_0x18;
	uint8_t adaptive_stream_index;
	uint8_t byte_at_0x2c;

	uint64_t key_pos;

	uint8_t *data; // not owned
	size_t data_size;
} ChiakiTakionAVPacket;

static inline uint8_t chiaki_takion_av_packet_audio_unit_size(ChiakiTakionAVPacket *packet)				{ return packet->units_in_frame_fec >> 8; }
static inline uint8_t chiaki_takion_av_packet_audio_source_units_count(ChiakiTakionAVPacket *packet)	{ return packet->units_in_frame_fec & 0xf; }
static inline uint8_t chiaki_takion_av_packet_audio_fec_units_count(ChiakiTakionAVPacket *packet)		{ return (packet->units_in_frame_fec >> 4) & 0xf; }

typedef ChiakiErrorCode (*ChiakiTakionAVPacketParse)(ChiakiTakionAVPacket *packet, ChiakiKeyState *key_state, uint8_t *buf, size_t buf_size);

typedef struct chiaki_takion_congestion_packet_t
{
	uint16_t word_0;
	uint16_t received;
	uint16_t lost;
} ChiakiTakionCongestionPacket;


typedef enum {
	CHIAKI_TAKION_EVENT_TYPE_CONNECTED,
	CHIAKI_TAKION_EVENT_TYPE_DISCONNECT,
	CHIAKI_TAKION_EVENT_TYPE_DATA,
	CHIAKI_TAKION_EVENT_TYPE_DATA_ACK,
	CHIAKI_TAKION_EVENT_TYPE_AV
} ChiakiTakionEventType;

typedef enum {
	CHIAKI_NONE_DISABLED = 0,  //(bits: 00)
	CHIAKI_AUDIO_DISABLED = 1, //(bits: 01)
	CHIAKI_VIDEO_DISABLED = 2, //(bits: 10)
	CHIAKI_AUDIO_VIDEO_DISABLED = 3 //(bits: 11)
} ChiakiDisableAudioVideo;

typedef struct chiaki_takion_event_t
{
	ChiakiTakionEventType type;
	union
	{
		struct
		{
			ChiakiTakionMessageDataType data_type;
			uint8_t *buf;
			size_t buf_size;
		} data;

		struct
		{
			ChiakiSeqNum32 seq_num;
		} data_ack;

		ChiakiTakionAVPacket *av;
	};
} ChiakiTakionEvent;

typedef void (*ChiakiTakionCallback)(ChiakiTakionEvent *event, void *user);

typedef struct chiaki_takion_connect_info_t
{
	ChiakiLog *log;
	struct sockaddr *sa;
	size_t sa_len;
	bool ip_dontfrag;
	ChiakiTakionCallback cb;
	void *cb_user;
	ChiakiDisableAudioVideo disable_audio_video;
	bool enable_crypt;
	bool enable_dualsense;
	uint8_t protocol_version;
	bool close_socket; // close socket when finishing takion
	ChiakiServiceType service_type; // REMOTE_PLAY / PSNOW / PSCLOUD (single source of truth)
	uint8_t psn_wrapper_type; // PSN wrapper type for Cloud Play (last octet of private IP)
	bool is_ping_handshake; // true if this takion connection is for ping handshake (senkusha), false for normal streaming
} ChiakiTakionConnectInfo;


typedef struct chiaki_takion_t
{
	ChiakiLog *log;
	uint8_t version;
	ChiakiServiceType service_type; // REMOTE_PLAY / PSNOW / PSCLOUD (single source of truth)
	uint8_t psn_wrapper_type; // PSN wrapper type for Cloud Play (last octet of private IP)
	bool is_ping_handshake; // true if this takion connection is for ping handshake (senkusha), false for normal streaming

	// Whether or not audio or video is disabled from further processing beyond basic ack
	ChiakiDisableAudioVideo disable_audio_video;
	/**
	 * Whether encryption should be used.
	 *
	 * If false, encryption and MACs are disabled completely.
	 *
	 * If true, encryption and MACs will be used depending on whether gkcrypt_local and gkcrypt_remote are non-null, respectively.
	 * However, if gkcrypt_remote is null, only control data packets are passed to the callback and all other packets are postponed until
	 * gkcrypt_remote is set, so it has been set, so eventually all MACs will be checked.
	 */
	bool enable_crypt;

	/**
	 * Array to be temporarily allocated when non-data packets come, enable_crypt is true, but gkcrypt_remote is NULL
	 * to not ignore any MACs in this period.
	 */
	struct chiaki_takion_postponed_packet_t *postponed_packets;
	size_t postponed_packets_size;
	size_t postponed_packets_count;

	ChiakiGKCrypt *gkcrypt_local; // if NULL (default), no gmac is calculated and nothing is encrypted
	uint64_t key_pos_local;
	ChiakiMutex gkcrypt_local_mutex;

	ChiakiGKCrypt *gkcrypt_remote; // if NULL (default), remote gmacs are IGNORED (!) and everything is expected to be unencrypted

	ChiakiReorderQueue data_queue;
	ChiakiTakionSendBuffer send_buffer;

	ChiakiTakionCallback cb;
	void *cb_user;
	chiaki_socket_t sock;
	ChiakiThread thread;
	ChiakiStopPipe stop_pipe;
	uint32_t tag_local;
	uint32_t tag_remote;
	bool close_socket;

	ChiakiSeqNum32 seq_num_local;
	ChiakiMutex seq_num_local_mutex;

	/**
	 * Advertised Receiver Window Credit
	 */
	uint32_t a_rwnd;

	ChiakiTakionAVPacketParse av_packet_parse;

	ChiakiKeyState key_state;

	bool enable_dualsense;
} ChiakiTakion;


CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_connect(ChiakiTakion *takion, ChiakiTakionConnectInfo *info, chiaki_socket_t *sock);
CHIAKI_EXPORT void chiaki_takion_close(ChiakiTakion *takion);

/**
 * Must be called from within the Takion thread, i.e. inside the callback!
 */
static inline void chiaki_takion_set_crypt(ChiakiTakion *takion, ChiakiGKCrypt *gkcrypt_local, ChiakiGKCrypt *gkcrypt_remote)
{
	takion->gkcrypt_local = gkcrypt_local;
	takion->gkcrypt_remote = gkcrypt_remote;
}

CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_packet_mac(ChiakiGKCrypt *crypt, uint8_t *buf, size_t buf_size, uint64_t key_pos, uint8_t *mac_out, uint8_t *mac_old_out, bool has_psn_wrapper);

/**
 * Get a new key pos and advance by data_size.
 *
 * Thread-safe while Takion is running.
 * @param key_pos pointer to write the new key pos to. will be 0 if encryption is disabled. Contents undefined on failure.
 */
CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_crypt_advance_key_pos(ChiakiTakion *takion, size_t data_size, uint64_t *key_pos);

/**
 * Send a datagram directly on the socket.
 *
 * Thread-safe while Takion is running.
 */
CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_send_raw(ChiakiTakion *takion, const uint8_t *buf, size_t buf_size);

/**
 * Calculate the MAC for the packet depending on the type derived from the first byte in buf,
 * assign MAC inside buf at the respective position and send the packet.
 *
 * If encryption is disabled, the MAC will be set to 0.
 */
CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_send(ChiakiTakion *takion, uint8_t *buf, size_t buf_size, uint64_t key_pos);

/**
 * Thread-safe while Takion is running.
 *
 * @param optional pointer to write the sequence number of the sent packet to
 */
CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_send_message_data(ChiakiTakion *takion, uint8_t chunk_flags, uint16_t channel, uint8_t *buf, size_t buf_size, ChiakiSeqNum32 *seq_num);

/**
 * Thread-safe while Takion is running.
 *
 * @param optional pointer to write the sequence number of the sent packet to
 */
CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_send_message_data_cont(ChiakiTakion *takion, uint8_t chunk_flags, uint16_t channel, uint8_t *buf, size_t buf_size, ChiakiSeqNum32 *seq_num);

/**
 * Thread-safe while Takion is running.
 */
CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_send_congestion(ChiakiTakion *takion, ChiakiTakionCongestionPacket *packet);

/**
 * Thread-safe while Takion is running.
 */
CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_send_feedback_state(ChiakiTakion *takion, ChiakiSeqNum16 seq_num, ChiakiFeedbackState *feedback_state);

CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_send_mic_packet(ChiakiTakion *takion, uint8_t *audio_packet, size_t packet_size, bool ps5);
/**
 * Thread-safe while Takion is running.
 */
CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_send_feedback_history(ChiakiTakion *takion, ChiakiSeqNum16 seq_num, uint8_t *payload, size_t payload_size);

#define CHIAKI_TAKION_V9_AV_HEADER_SIZE_VIDEO 0x17
#define CHIAKI_TAKION_V9_AV_HEADER_SIZE_AUDIO 0x12

CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_v9_av_packet_parse(ChiakiTakionAVPacket *packet, ChiakiKeyState *key_state, uint8_t *buf, size_t buf_size);

/**
 * Takion protocol feature ids.
 *
 * Both ends gate optional behaviour through one shared predicate: each protocol
 * version carries a feature count, and a feature is available when its id is below
 * that count. The counts are 5, 6, 7, 8, 8, 8, 13, 13, 13, 16, 17, 19 for versions 9
 * through 20 (versions 10-12 additionally mask out a couple of low ids), and a
 * version above 20 is rejected outright rather than treated as newer.
 *
 * Only the ids this code needs are named. The two that matter for the pad speaker:
 * PADSPK gates both the padspk channel declarations and the entire AUDIOSTATE
 * message, and AUDIO_CHANNELNUM gates the CHANNELNUM variant of it specifically.
 */
typedef enum chiaki_takion_feature_t
{
	/** Haptics channels. Available from version 12, which is why they already work. */
	CHIAKI_TAKION_FEATURE_HAPTIC = 7,
	/**
	 * Pad speaker channels, and with them the whole AUDIOSTATE message -- the host
	 * refuses to accept any audio state update without this feature, so a session
	 * that lacks it can have the padspk channels allocated and still never be able to
	 * ask for them to be fed. Available from version 15.
	 */
	CHIAKI_TAKION_FEATURE_PADSPK = 10,
	/** AUDIOSTATE type CHANNELNUM. Available from version 18. */
	CHIAKI_TAKION_FEATURE_AUDIO_CHANNELNUM = 15,
	/**
	 * An audio port mask wider than 8 bits. Without it the client clamps the mask it
	 * sends to 0xff. Available from version 19.
	 */
	CHIAKI_TAKION_FEATURE_AUDIO_WIDE_PORT_MASK = 16,
} ChiakiTakionFeature;

/** Lowest protocol version carrying any feature table at all. */
#define CHIAKI_TAKION_PROTOCOL_VERSION_MIN 9

/**
 * Whether a Takion protocol version carries a given feature, by the same table both
 * ends of the connection use. Versions outside [9, 20] carry nothing.
 */
CHIAKI_EXPORT bool chiaki_takion_protocol_feature_supported(uint8_t version, ChiakiTakionFeature feature);

/** Takion protocol version whose feature table the host caps out at. */
#define CHIAKI_TAKION_PROTOCOL_VERSION_MAX 20

/**
 * AV header sizes per Takion protocol version, as the host's own lookup tables give
 * them. Indexed by version - 9, i.e. entry 0 is version 9 and entry 11 is version 20.
 *
 * Versions 9 and 12 agree with the CHIAKI_TAKION_V9_/V12_ constants below, which is
 * what makes the rest of the row trustworthy:
 *
 *   version : 9   10  11  12  13  14  15  16  17  18  19  20
 *   base    : 11  11  11  11  11  11  11  11  11  11  11  19
 *   audio   : 18  18  19  19  19  19  20  20  20  20  20  28
 *   video   : 23  23  23  23  23  23  23  23  23  23  23  31
 *
 * Reading that: versions 15-19 differ from version 12 by exactly one extra byte, and
 * it is in the audio-specific part of the header -- base and video are unchanged.
 * Version 20 instead adds 8 bytes to the common prefix, which is why audio and video
 * both grow by 8 on top of that.
 */
#define CHIAKI_TAKION_AV_HEADER_SIZE_TABLE_FIRST_VERSION 9
#define CHIAKI_TAKION_AV_HEADER_SIZE_BASE_TABLE  { 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 11, 19 }
#define CHIAKI_TAKION_AV_HEADER_SIZE_AUDIO_TABLE { 18, 18, 19, 19, 19, 19, 20, 20, 20, 20, 20, 28 }
#define CHIAKI_TAKION_AV_HEADER_SIZE_VIDEO_TABLE { 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 23, 31 }

#define CHIAKI_TAKION_V12_AV_HEADER_SIZE_VIDEO 0x17
#define CHIAKI_TAKION_V12_AV_HEADER_SIZE_AUDIO 0x13

CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_v12_av_packet_parse(ChiakiTakionAVPacket *packet, ChiakiKeyState *key_state, uint8_t *buf, size_t buf_size);

#define CHIAKI_TAKION_V7_AV_HEADER_SIZE_BASE					0x12
#define CHIAKI_TAKION_V7_AV_HEADER_SIZE_VIDEO_ADD				0x3
#define CHIAKI_TAKION_V7_AV_HEADER_SIZE_NALU_INFO_STRUCTS_ADD	0x3

CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_v7_av_packet_format_header(uint8_t *buf, size_t buf_size, size_t *header_size_out, ChiakiTakionAVPacket *packet);

CHIAKI_EXPORT ChiakiErrorCode chiaki_takion_v7_av_packet_parse(ChiakiTakionAVPacket *packet, ChiakiKeyState *key_state, uint8_t *buf, size_t buf_size);

#define CHIAKI_TAKION_CONGESTION_PACKET_SIZE 0xf

CHIAKI_EXPORT void chiaki_takion_format_congestion(uint8_t *buf, ChiakiTakionCongestionPacket *packet, uint64_t key_pos);

#ifdef __cplusplus
}
#endif

#endif // CHIAKI_TAKION_H
