// AGENT-DONE(claude): vehicle-damage-model
package io.github.jwyoon1220.dncity.client.render

import io.github.jwyoon1220.dncity.Dncity
import io.github.jwyoon1220.dncity.ballistics.vehicle.Face
import io.github.jwyoon1220.dncity.ballistics.vehicle.HitOutcome
import io.github.jwyoon1220.dncity.ballistics.vehicle.PenetratorKind
import io.github.jwyoon1220.dncity.ballistics.vehicle.ReactiveType
import io.github.jwyoon1220.dncity.ballistics.vehicle.damage.DestroyCause
import io.github.jwyoon1220.dncity.ballistics.vehicle.damage.RayKind
import io.github.jwyoon1220.dncity.ballistics.network.VehicleHitCamState
import io.github.jwyoon1220.dncity.ballistics.network.VehicleHitReportPayload
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.LayeredDraw
import net.minecraft.client.renderer.RenderType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * War Thunder-style hit camera: after a hit on an armored vehicle, a panel on the right shows the vehicle from the side and
 * from above with its armor boxes and internal modules (grey intact, orange damaged, red destroyed, bright when this hit
 * reached them), the shell's path (white outside; red shell / cyan jet inside; yellow for a ricochet), the spall cone (orange
 * lines) and blast fragments (yellow) with the blast radius, then the numbers (plate, angle, effective armor vs penetration,
 * ERA/NERA, overmatch), what was damaged and what happened (crew knocked out, fire, ammo detonation, vehicle destroyed).
 * Fed by [VehicleHitCamState] ([VehicleHitReportPayload]); fades out after [SHOW_MS].
 */
object VehicleHitCamOverlay : LayeredDraw.Layer {
    val ID: ResourceLocation = ResourceLocation.fromNamespaceAndPath(Dncity.ID, "vehicle_hit_cam")

    private const val SHOW_MS = 6500L
    private const val FADE_MS = 1000L
    private const val PANEL_W = 240
    private const val VIEW_H = 70
    private const val PAD = 6
    private const val MAX_EVENT_LINES = 6

    /** Box edges for SuperbWarfare's `OBB.getVertices()` corner order. */
    private val EDGES = intArrayOf(0, 1, 1, 2, 2, 3, 3, 0, 4, 5, 5, 6, 6, 7, 7, 4, 0, 4, 1, 5, 2, 6, 3, 7)

    override fun render(graphics: GuiGraphics, delta: DeltaTracker) {
        val report = VehicleHitCamState.latest ?: return
        val age = System.currentTimeMillis() - VehicleHitCamState.receivedAtMs
        if (age > SHOW_MS) {
            VehicleHitCamState.clear()
            return
        }
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui) return
        val alpha = if (age > SHOW_MS - FADE_MS) (SHOW_MS - age).toFloat() / FADE_MS else 1f
        val outcome = HitOutcome.entries.getOrElse(report.outcome) { HitOutcome.NOT_PENETRATED }

        val font = mc.font
        val lines = textLines(report, outcome)
        val events = eventLines(report)
        val panelH = PAD + 10 + 2 * (VIEW_H + 4) + 14 + lines.size * 10 + events.size * 10 + PAD
        val x0 = graphics.guiWidth() - PANEL_W - 8
        val y0 = max(4, (graphics.guiHeight() - panelH) / 2)

        graphics.fill(x0, y0, x0 + PANEL_W, y0 + panelH, argb(0.75f * alpha, 0x0E1216))
        graphics.fill(x0, y0, x0 + PANEL_W, y0 + 1, argb(alpha, outcomeColor(outcome)))

        val title = Component.translatable(entityName(report.vehicleType)).append(" · ").append(plateName(report))
        graphics.drawString(font, title, x0 + PAD, y0 + PAD, argb(alpha, 0xE6E6E6), false)

        val viewTop = y0 + PAD + 12
        val viewW = PANEL_W - 2 * PAD
        // side view: front to the right, up is up; top view: front to the right, the vehicle's left is up
        drawView(graphics, report, outcome, x0 + PAD, viewTop, viewW, VIEW_H, alpha) { p -> floatArrayOf(p[2], -p[1]) }
        drawView(graphics, report, outcome, x0 + PAD, viewTop + VIEW_H + 4, viewW, VIEW_H, alpha) { p -> floatArrayOf(p[2], -p[0]) }

        var y = viewTop + 2 * (VIEW_H + 4) + 2
        val verdict = if (report.destroyed >= 0) Component.translatable("dncity.hitcam.destroyed") else Component.translatable("dncity.hitcam.outcome.${outcome.name.lowercase()}")
        graphics.drawString(font, verdict, x0 + PAD, y, argb(alpha, if (report.destroyed >= 0) 0xFF2A2A else outcomeColor(outcome)), true)
        y += 14
        for (line in lines) {
            graphics.drawString(font, line, x0 + PAD, y, argb(alpha, 0xC8C8C8), false)
            y += 10
        }
        for (line in events) {
            graphics.drawString(font, line, x0 + PAD, y, argb(alpha, 0xFF8A70), false)
            y += 10
        }
    }

    private fun textLines(r: VehicleHitReportPayload, outcome: HitOutcome): List<Component> {
        val kind = PenetratorKind.entries.getOrElse(r.kind) { PenetratorKind.KE }
        val lines = mutableListOf<Component>(
            Component.translatable("dncity.hitcam.armor", mm(r.armorMm), "%.0f".format(r.angleDeg), mm(r.effectiveMm)),
            Component.translatable("dncity.hitcam.penetration", mm(r.penetrationMm), Component.translatable("dncity.hitcam.kind.${kind.name.lowercase()}")),
        )
        ReactiveType.entries.getOrNull(r.reactiveType)?.let {
            lines += Component.translatable("dncity.hitcam.reactive.${it.name.lowercase()}", mm(r.reactiveMm))
        }
        if (r.overmatch) lines += Component.translatable("dncity.hitcam.overmatch")
        if (outcome == HitOutcome.RICOCHET && r.ricochetChance < 1f) {
            lines += Component.translatable("dncity.hitcam.ricochet_chance", "%.0f".format(r.ricochetChance * 100))
        }
        if (outcome == HitOutcome.PENETRATED) {
            val spall = r.rays.count { it.kind == RayKind.SPALL.ordinal }
            if (r.rays.isEmpty()) lines += Component.translatable("dncity.hitcam.depth", "%.1f".format(r.depthBlocks))
            else lines += Component.translatable("dncity.hitcam.spall", spall)
            val modules = r.modules.map { moduleName(it) }
            lines += if (modules.isEmpty()) Component.translatable("dncity.hitcam.no_modules")
            else Component.translatable("dncity.hitcam.modules", joined(modules))
        }
        return lines
    }

    private fun eventLines(r: VehicleHitReportPayload): List<Component> {
        val out = r.events.filter { !it.startsWith("vehicle_destroyed") }.take(MAX_EVENT_LINES).map { e ->
            val type = e.substringBefore(':')
            val module = e.substringAfter(':', "")
            if (module.isEmpty()) Component.translatable("dncity.hitcam.event.$type")
            else Component.translatable("dncity.hitcam.event.$type", moduleName(module))
        }.toMutableList()
        DestroyCause.entries.getOrNull(r.destroyed)?.let { out += Component.translatable("dncity.hitcam.cause.${it.name.lowercase()}") }
        return out
    }

    private fun moduleName(id: String): Component = Component.translatableWithFallback("dncity.hitcam.module.$id", id.replace('_', ' '))

    private fun drawView(
        graphics: GuiGraphics, r: VehicleHitReportPayload, outcome: HitOutcome,
        x: Int, y: Int, w: Int, h: Int, alpha: Float, project: (FloatArray) -> FloatArray,
    ) {
        graphics.fill(x, y, x + w, y + h, argb(0.35f * alpha, 0x000000))
        // fit every box in the view, same scale both ways
        var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE; var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
        for (box in r.boxes) for (i in 0 until 8) {
            val p = project(floatArrayOf(box.corners[i * 3], box.corners[i * 3 + 1], box.corners[i * 3 + 2]))
            minU = min(minU, p[0]); maxU = max(maxU, p[0]); minV = min(minV, p[1]); maxV = max(maxV, p[1])
        }
        if (minU > maxU) return
        val margin = 6f
        val scale = min((w - 2 * margin) / max(maxU - minU, 0.1f), (h - 2 * margin) / max(maxV - minV, 0.1f))
        val cu = (minU + maxU) / 2; val cv = (minV + maxV) / 2
        fun screen(p: FloatArray): FloatArray {
            val q = project(p)
            return floatArrayOf(x + w / 2f + (q[0] - cu) * scale, y + h / 2f + (q[1] - cv) * scale)
        }

        val buffer = graphics.bufferSource().getBuffer(RenderType.gui())
        val matrix = graphics.pose().last().pose()
        fun line(a: FloatArray, b: FloatArray, color: Int, width: Float) {
            val dx = b[0] - a[0]; val dy = b[1] - a[1]
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-3f) return
            val nx = -dy / len * width / 2; val ny = dx / len * width / 2
            // both windings: the GUI render type may cull back faces
            for (flip in 0..1) {
                val s = if (flip == 0) 1f else -1f
                buffer.addVertex(matrix, a[0] + nx * s, a[1] + ny * s, 0f).setColor(color)
                buffer.addVertex(matrix, b[0] + nx * s, b[1] + ny * s, 0f).setColor(color)
                buffer.addVertex(matrix, b[0] - nx * s, b[1] - ny * s, 0f).setColor(color)
                buffer.addVertex(matrix, a[0] - nx * s, a[1] - ny * s, 0f).setColor(color)
            }
        }
        fun box(corners: FloatArray, color: Int, width: Float) {
            val pts = Array(8) { i -> screen(floatArrayOf(corners[i * 3], corners[i * 3 + 1], corners[i * 3 + 2])) }
            for (e in EDGES.indices step 2) line(pts[EDGES[e]], pts[EDGES[e + 1]], color, width)
        }

        for (b in r.boxes.sortedBy { it.hit }) {
            val color = if (b.hit) argb(alpha, if (outcome == HitOutcome.PENETRATED) 0xFF5040 else 0xE0C040) else argb(0.55f * alpha, 0x8A9AA8)
            box(b.corners, color, if (b.hit) 1.2f else 0.7f)
        }
        for (m in r.moduleBoxes.sortedBy { it.hit }) {
            val rgb = when (m.condition) { 2 -> 0xFF3030; 1 -> 0xFFA040; else -> 0x6E8C6E }
            box(m.corners, argb((if (m.hit) 1f else 0.5f) * alpha, rgb), if (m.hit) 1.1f else 0.6f)
        }

        val blastRadius = r.blast[3]
        if (blastRadius > 0) {
            val c = floatArrayOf(r.blast[0], r.blast[1], r.blast[2])
            val center = screen(c)
            val rim = screen(floatArrayOf(c[0], c[1], c[2] + blastRadius))
            val radius = sqrt((rim[0] - center[0]) * (rim[0] - center[0]) + (rim[1] - center[1]) * (rim[1] - center[1]))
            var prev = floatArrayOf(center[0] + radius, center[1])
            for (i in 1..24) {
                val a = 2 * PI * i / 24
                val next = floatArrayOf(center[0] + radius * cos(a).toFloat(), center[1] + radius * sin(a).toFloat())
                line(prev, next, argb(0.8f * alpha, 0xFFE070), 0.8f)
                prev = next
            }
        }
        for (ray in r.rays) {
            val (rgb, width) = when (RayKind.entries.getOrElse(ray.kind) { RayKind.SPALL }) {
                RayKind.SHELL -> 0xFF3020 to 1.6f
                RayKind.JET -> 0x60E0FF to 1.6f
                RayKind.SPALL -> 0xFFA040 to 0.6f
                RayKind.BLAST_FRAGMENT -> 0xFFE070 to 0.5f
            }
            line(screen(ray.from), screen(ray.to), argb((if (ray.hit) 0.95f else 0.55f) * alpha, rgb), width)
        }

        val entry = r.entry
        val back = FloatArray(3) { entry[it] - r.direction[it] * 3f }
        line(screen(back), screen(entry), argb(alpha, 0xFFFFFF), 1.4f)
        val stopColor = when (outcome) {
            HitOutcome.PENETRATED -> 0xFF3020
            HitOutcome.RICOCHET -> 0xFFD040
            HitOutcome.NOT_PENETRATED -> 0x60A0FF
        }
        if (r.rays.isEmpty()) line(screen(entry), screen(r.stop), argb(alpha, stopColor), 1.6f)
        val e = screen(entry)
        graphics.fill((e[0] - 1.5f).toInt(), (e[1] - 1.5f).toInt(), (e[0] + 1.5f).toInt(), (e[1] + 1.5f).toInt(), argb(alpha, stopColor))
        graphics.flush()
    }

    private fun plateName(r: VehicleHitReportPayload): Component {
        val face = Face.entries.getOrElse(r.face) { Face.FRONT }
        return Component.translatable("dncity.hitcam.plate", Component.translatable("dncity.hitcam.zone.${r.zone}"),
            Component.translatable("dncity.hitcam.face.${face.name.lowercase()}"))
    }

    private fun entityName(id: String): String =
        ResourceLocation.tryParse(id)?.let { BuiltInRegistries.ENTITY_TYPE.get(it).descriptionId } ?: id

    private fun joined(parts: List<Component>): Component {
        val out = Component.empty()
        parts.forEachIndexed { i, c -> if (i > 0) out.append(", "); out.append(c) }
        return out
    }

    private fun mm(v: Float) = "%.0f".format(v)

    private fun outcomeColor(o: HitOutcome) = when (o) {
        HitOutcome.PENETRATED -> 0xFF4A3A
        HitOutcome.NOT_PENETRATED -> 0x7FB0FF
        HitOutcome.RICOCHET -> 0xFFD040
    }

    private fun argb(alpha: Float, rgb: Int): Int = ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (rgb and 0xFFFFFF)
}
