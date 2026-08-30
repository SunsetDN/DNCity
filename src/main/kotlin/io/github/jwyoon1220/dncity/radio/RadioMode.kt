package io.github.jwyoon1220.dncity.radio

/** Which of [io.github.jwyoon1220.dncity.voice.RadioTransmitter]'s two codecs a [RadioMode]
 * transmits with -- OPUS for the modes whose "radio" character comes entirely from
 * [io.github.jwyoon1220.dncity.voice.RadioChannel]'s receive-side bandpass/static DSP, CODEC2 for
 * the sideband modes real SSB radios carry as low-bitrate digital voice in this project's model
 * (see [io.github.jwyoon1220.dncity.voice.Codec2Codec]'s doc comment for why that's the deliberate
 * simplification here rather than analog SSB demodulation). */
enum class VoiceCodec { OPUS, CODEC2 }

/**
 * Voice mode used on a radio slot, restricted per [RadioBandGroup] to the modes that band family
 * would realistically carry (see that enum's `modes` field). Each defines the audio bandpass
 * window a real receiver's IF filter would apply, how much static rides under a clean signal at
 * point-blank range ([baseNoiseLevel]), and how gracefully it degrades toward the edge of range
 * ([fadeExponent]/[fadeCoefficient], consumed by [RadioVoice.staticLevel]).
 *
 * Real-world characteristics modeled:
 * - **AM** -- full carrier + both sidebands, 300-3000Hz voice bandwidth. No fade immunity: an
 *   envelope detector's output amplitude (and therefore its noise) tracks carrier strength
 *   almost linearly, and selective fading (one sideband fading independently of the other on a
 *   skywave path) adds audible distortion/"growl" as signal weakens -- modeled as a bit of extra
 *   crackle beyond plain noise (see [io.github.jwyoon1220.dncity.voice.RadioChannel]).
 * - **SAM** (synchronous AM) -- same bandwidth/carrier as AM, but a synchronous detector locks a
 *   local oscillator to the carrier's phase instead of just rectifying the envelope, which is
 *   immune to the selective-fading distortion that makes plain AM "growl" as a skywave signal
 *   fades -- historically the mode shortwave DXers reach for over AM on a marginal HF signal.
 *   Modeled as a gentler fade curve than AM at the same distance, with less crackle.
 * - **USB/LSB** -- suppressed-carrier single sideband, narrower (300-2700Hz) and ~6dB more
 *   efficient than AM for the same transmit power, hence the lowest clean-signal noise floor of
 *   the voice-bandwidth modes. By amateur/HF convention USB is used above 10MHz and LSB below --
 *   enforced by which of the two a HF radio's band group actually offers per its tuned range in
 *   practice, not by this enum (which just supplies identical DSP characteristics for the mirror
 *   sideband).
 * - **CW** (Morse) -- not a voice mode in reality at all, but modeled here as the extreme case of
 *   the "narrower filter = better SNR" curve every other mode sits on: a real CW receiver's filter
 *   is only ~200-500Hz wide (vs. ~2400-3000Hz for voice), which alone is worth roughly 8-10dB of
 *   noise reduction (10*log10(3000/300)) -- the reason CW carries the best range for a given
 *   transmit power of any mode here. [lowCutoffHz]/[highCutoffHz] reflect that filter width
 *   directly, which as a side effect makes voice audio passed through it sound like a thin,
 *   barely-intelligible whistle -- an intentional nod to CW not really being a voice mode, not a
 *   bug: use it for range, not clarity.
 * - **NBFM** -- narrowband FM, the real mode land-mobile/marine/aviation VHF-UHF handhelds use for
 *   voice (2.5kHz deviation, 300-3000Hz audio -- *not* the ~15kHz-wide high-fidelity FM broadcast
 *   uses, which needs a channel far wider than a two-way voice radio is allotted). Keeps FM's
 *   "capture effect" static curve (see [RadioVoice.staticLevel]) but with voice-bandwidth cutoffs
 *   instead of broadcast ones.
 * - **FM** -- the wideband, high-fidelity mode actual FM *broadcast* stations use (50-15000Hz --
 *   real FM broadcast is closer to 30-15000Hz, rounded here), as opposed to [NBFM]'s narrow
 *   two-way-voice channel. Only meaningful on [io.github.jwyoon1220.dncity.item.RadioItem]s built
 *   to actually send/receive a broadcast-width signal (see [RadioBandGroup.BROADCAST]) -- same
 *   capture-effect noise curve as NBFM, just the wider passband.
 */
enum class RadioMode(
    val lowCutoffHz: Double,
    val highCutoffHz: Double,
    val baseNoiseLevel: Double,
    val codec: VoiceCodec,
) {
    AM(lowCutoffHz = 300.0, highCutoffHz = 3000.0, baseNoiseLevel = 0.12, codec = VoiceCodec.OPUS),
    SAM(lowCutoffHz = 300.0, highCutoffHz = 3000.0, baseNoiseLevel = 0.08, codec = VoiceCodec.OPUS),
    USB(lowCutoffHz = 300.0, highCutoffHz = 2700.0, baseNoiseLevel = 0.06, codec = VoiceCodec.CODEC2),
    LSB(lowCutoffHz = 300.0, highCutoffHz = 2700.0, baseNoiseLevel = 0.06, codec = VoiceCodec.CODEC2),
    CW(lowCutoffHz = 300.0, highCutoffHz = 550.0, baseNoiseLevel = 0.015, codec = VoiceCodec.OPUS),
    NBFM(lowCutoffHz = 300.0, highCutoffHz = 3000.0, baseNoiseLevel = 0.03, codec = VoiceCodec.OPUS),
    FM(lowCutoffHz = 50.0, highCutoffHz = 15000.0, baseNoiseLevel = 0.03, codec = VoiceCodec.OPUS),
}
