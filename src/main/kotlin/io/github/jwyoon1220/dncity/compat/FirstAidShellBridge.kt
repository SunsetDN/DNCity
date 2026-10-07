// AGENT-DONE(claude): shell-ballistics
package io.github.jwyoon1220.dncity.compat

import ichttt.mods.firstaid.FirstAidConfig
import ichttt.mods.firstaid.api.damage.HitProfile
import ichttt.mods.firstaid.api.damage.HitProfiles
import io.github.jwyoon1220.dncity.ballistics.Shell
import io.github.jwyoon1220.dncity.ballistics.ShellRegistry

/**
 * Tells First Aid what the ammunition behind a hit is. First Aid asks once per hit on a player; a hit whose projectile is
 * a known [Shell] is scaled by its power, ignores armor by its penetration, and wounds by its size (bleeding, fractures,
 * how much of an emptied limb is passed on). Anything else is handled exactly as before.
 *
 * Only registered when First Aid is loaded, this class is never touched otherwise.
 */
object FirstAidShellBridge {
    fun register() {
        HitProfiles.register { source -> ShellRegistry.resolve(source)?.let(::toProfile) }
    }

    private fun toProfile(shell: Shell): HitProfile {
        val server = FirstAidConfig.SERVER
        return HitProfile(
            shell.damageMultiplier,
            shell.damageIsLimbHp,
            shell.penetration,
            shell.effectiveBleedChanceBonus,
            shell.effectiveHeavyBleedHitFraction(server.heavyBleedHitFraction.get().toFloat()),
            shell.fractureChanceBonus,
            shell.effectiveOverkillFactor(server.limbOverkillFactor.get().toFloat()),
            shell.explosive,
        )
    }
}
