// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include <chiaki/audio.h>

#include <string.h>

#ifdef _WIN32
#include <winsock2.h>
#else
#include <netinet/in.h>
#endif

#ifdef __SWITCH__
#include <arpa/inet.h>
#endif

void chiaki_audio_header_load(ChiakiAudioHeader *audio_header, const uint8_t *buf)
{
	audio_header->channels = buf[0];
	audio_header->bits = buf[1];
	audio_header->rate = ntohl(*((chiaki_unaligned_uint32_t *)(buf + 2)));
	audio_header->frame_size = ntohl(*((chiaki_unaligned_uint32_t *)(buf + 6)));
	audio_header->unknown = ntohl(*((chiaki_unaligned_uint32_t *)(buf + 0xa)));
}

void chiaki_audio_header_save(ChiakiAudioHeader *audio_header, uint8_t *buf)
{
	buf[0] = audio_header->bits;
	buf[1] = audio_header->channels;
	*((chiaki_unaligned_uint32_t *)(buf + 2)) = htonl(audio_header->rate);
	*((chiaki_unaligned_uint32_t *)(buf + 6)) = htonl(audio_header->frame_size);
	*((chiaki_unaligned_uint32_t *)(buf + 0xa)) = htonl(audio_header->unknown);
}

void chiaki_audio_header_set(ChiakiAudioHeader * audio_header, uint8_t channels, uint8_t bits, uint32_t rate, uint32_t frame_size)
{
	audio_header->channels = channels;
	audio_header->bits = bits;
	audio_header->rate = rate;
	audio_header->frame_size = frame_size;
	audio_header->unknown = 1;
}

// ---------------------------------------------------------------------------
// AUDIOSTATE payload blobs. See the layout table in audio.h.
//
// Every field goes out in native byte order: the client memcpy's straight out of the
// sceAudioOut2 structs rather than serialising, so these are host-endian, unlike the
// big-endian audio header above.
// ---------------------------------------------------------------------------

CHIAKI_EXPORT size_t chiaki_audio_state_build_hrtf(uint8_t *buf, size_t buf_size, uint32_t hrtf_id)
{
	if(!buf || buf_size < CHIAKI_AUDIO_STATE_HRTF_SIZE)
		return 0;
	memcpy(buf, &hrtf_id, sizeof(hrtf_id));
	return CHIAKI_AUDIO_STATE_HRTF_SIZE;
}

CHIAKI_EXPORT size_t chiaki_audio_state_build_flags(uint8_t *buf, size_t buf_size,
		uint8_t speaker_kind, uint32_t available_bits, uint32_t dword_2)
{
	if(!buf || buf_size < CHIAKI_AUDIO_STATE_FLAGS_SIZE)
		return 0;
	buf[0] = speaker_kind;
	memcpy(buf + 1, &available_bits, sizeof(available_bits));
	memcpy(buf + 5, &dword_2, sizeof(dword_2));
	return CHIAKI_AUDIO_STATE_FLAGS_SIZE;
}

CHIAKI_EXPORT size_t chiaki_audio_state_build_angle(uint8_t *buf, size_t buf_size,
		const ChiakiAudioStateAngle *angles)
{
	if(!buf || !angles || buf_size < CHIAKI_AUDIO_STATE_ANGLE_SIZE)
		return 0;
	size_t pos = 0;
	for(size_t i = 0; i < CHIAKI_AUDIO_STATE_SPEAKER_ANGLES; i++)
	{
		memcpy(buf + pos, &angles[i].a, sizeof(uint16_t));
		pos += sizeof(uint16_t);
		memcpy(buf + pos, &angles[i].b, sizeof(uint16_t));
		pos += sizeof(uint16_t);
	}
	return pos;
}

CHIAKI_EXPORT size_t chiaki_audio_state_build_full(uint8_t *buf, size_t buf_size,
		uint8_t speaker_kind, uint32_t available_bits, uint32_t dword_2,
		const ChiakiAudioStateAngle *angles)
{
	if(!buf || buf_size < CHIAKI_AUDIO_STATE_FULL_SIZE)
		return 0;
	size_t pos = chiaki_audio_state_build_flags(buf, buf_size, speaker_kind, available_bits, dword_2);
	if(!pos)
		return 0;
	size_t angle_size = chiaki_audio_state_build_angle(buf + pos, buf_size - pos, angles);
	if(!angle_size)
		return 0;
	return pos + angle_size;
}

CHIAKI_EXPORT size_t chiaki_audio_state_build_port_states(uint8_t *buf, size_t buf_size,
		uint8_t group, const ChiakiAudioStatePort *ports)
{
	if(!buf || !ports || buf_size < CHIAKI_AUDIO_STATE_PORTSTATES_SIZE)
		return 0;

	memset(buf, 0, CHIAKI_AUDIO_STATE_PORTSTATES_SIZE);
	buf[0] = group;

	// The 32-byte group block is field-major, not port-major: all four word_0 first,
	// then all four word_1, then all four dword_2.
	uint8_t *block = buf + 1;
	for(size_t i = 0; i < CHIAKI_AUDIO_STATE_PORTS_PER_GROUP; i++)
	{
		memcpy(block + i * sizeof(uint16_t), &ports[i].output, sizeof(uint16_t));
		memcpy(block + 0x08 + i * sizeof(uint16_t), &ports[i].volume, sizeof(uint16_t));
		memcpy(block + 0x10 + i * sizeof(uint32_t), &ports[i].flag, sizeof(uint32_t));
	}

	return CHIAKI_AUDIO_STATE_PORTSTATES_SIZE;
}
