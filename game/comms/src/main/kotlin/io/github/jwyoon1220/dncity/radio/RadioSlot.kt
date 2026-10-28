package io.github.jwyoon1220.dncity.radio

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder

data class RadioSlot(
    val frequencyKhz: Double,
    val mode: RadioMode,
    val enabled: Boolean,
) {
    val band: RadioBand? get() = RadioBand.fromFrequencyKhz(frequencyKhz)

    companion object {
        private val MODE_CODEC: Codec<RadioMode> = Codec.STRING.xmap(RadioMode::valueOf, RadioMode::name)

        val CODEC: Codec<RadioSlot> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.DOUBLE.fieldOf("frequency_khz").forGetter(RadioSlot::frequencyKhz),
                MODE_CODEC.fieldOf("mode").forGetter(RadioSlot::mode),
                Codec.BOOL.fieldOf("enabled").forGetter(RadioSlot::enabled),
            ).apply(instance, ::RadioSlot)
        }

        fun empty() = RadioSlot(frequencyKhz = 0.0, mode = RadioMode.AM, enabled = false)
    }
}

/** Persisted on the radio ItemStack. [slots] size is fixed to the item's tier. */
data class RadioData(
    val slots: List<RadioSlot>,
    val activeSlot: Int,
    val powered: Boolean,
) {
    companion object {
        val CODEC: Codec<RadioData> = RecordCodecBuilder.create { instance ->
            instance.group(
                RadioSlot.CODEC.listOf().fieldOf("slots").forGetter(RadioData::slots),
                Codec.INT.fieldOf("active_slot").forGetter(RadioData::activeSlot),
                Codec.BOOL.fieldOf("powered").forGetter(RadioData::powered),
            ).apply(instance, ::RadioData)
        }

        fun default(tier: Int) = RadioData(
            slots = List(tier) { RadioSlot.empty() },
            activeSlot = 0,
            powered = false,
        )
    }
}
