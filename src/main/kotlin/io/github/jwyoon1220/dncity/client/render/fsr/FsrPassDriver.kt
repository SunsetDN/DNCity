package io.github.jwyoon1220.dncity.client.render.fsr

/**
 * TODO(FSR2): the per-frame FSR2 invocation. Signature-only scaffold -- see AGENTS.md's
 * "Architecture: FSR2 temporal upscaling" for the full design; summarized here for the call site.
 *
 * Called from a new hook at (Iris's, not DNCity's)
 * `net.irisshaders.iris.pipeline.FinalPassRenderer.renderFinalPass()`, right after the
 * shaderpack's (real, or DNCity's bundled passthrough --
 * see `net.irisshaders.iris.pipeline.Fsr2PassthroughPipelineLoader`) final pass has written its
 * composited output into `main`'s color buffer, and right before that method's own
 * `main.bindWrite(true)` call (see that method's own TODO comment for the exact line). Not
 * actually wired up yet -- Iris's side of that hook is also unimplemented in this scaffold.
 *
 * Drives a manually-managed `com.mojang.blaze3d.shaders.ShaderInstance` (not vanilla's
 * `PostChain`) because FSR2's internal passes read/write buffers at *both* the internal render
 * resolution and the output display resolution within the same pass -- `PostChain`'s model
 * assumes one shared resolution for every target it manages, which can't express that (same
 * reasoning documented for the earlier, superseded FSR1-only version of this plan).
 *
 * Inputs each invocation needs (none of these accessors exist yet on the Iris side -- see the
 * TODOs in `net.irisshaders.iris.targets.RenderTargets`, `net.irisshaders.iris.uniforms.
 * CapturedRenderingState`, and `net.irisshaders.iris.uniforms.MatrixUniforms`):
 * - Composited color (`main`'s current color texture at the point this runs).
 * - Depth (`depthtex0`, already exposed by Iris today via `RenderTargets.getDepthTexture()`).
 * - Motion vectors (`RenderTargets.getMotionVectorsTexture()`, once A1 is implemented).
 * - Current + previous jitter offsets (`CapturedRenderingState.getCurrentJitterX/Y` /
 *   `getPreviousJitterX/Y`, already scaffolded as plain data fields).
 * - Previous camera matrices for static-terrain reprojection (`gbufferPreviousModelView/
 *   Projection` -- currently GLSL-uniform-only, see `MatrixUniforms`'s TODO for the Java-side
 *   accessor gap that needs filling first).
 * - History buffer(s) from [FsrRenderTargets].
 */
object FsrPassDriver {
    /**
     * TODO(FSR2): runs the EASU-equivalent reconstruction + upscale + RCAS-equivalent sharpen
     * passes and writes the result into Iris's `main` render target. See class doc for the full
     * set of inputs this needs and where they come from.
     */
    fun runFsr2Pass() {
        TODO("FSR2: FsrPassDriver.runFsr2Pass not implemented -- see AGENTS.md 'Architecture: FSR2 temporal upscaling'")
    }
}
