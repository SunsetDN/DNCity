// TODO(FSR2): placeholder -- NOT real shader math. Must be ported from AMD's MIT-licensed FSR2
// reference (see FSR2-LICENSE.txt in this directory) into GLSL #version 150 (Minecraft's
// ShaderInstance core-shader convention), following the reconstruction+upscale algorithm: sample
// the low-res color history using the motion-vector-guided reprojection, accumulate/reject
// history per FSR2's standard disocclusion heuristics, and produce the upscaled output.
//
// Expected uniform/sampler inputs (names not final -- coordinate with FsrPassDriver.kt once real):
//   uniform sampler2D DiffuseSampler;   // this frame's low-res composited color
//   uniform sampler2D DepthSampler;     // depthtex0 at internal render resolution
//   uniform sampler2D MotionVectorSampler; // RenderTargets.getMotionVectorsTexture()
//   uniform sampler2D HistorySampler;   // FsrRenderTargets's previous-frame accumulated output
//   uniform vec2 InputSize;             // internal render resolution
//   uniform vec2 OutputSize;            // post-upscale output resolution
//   uniform vec2 Jitter;                // this frame's jitter offset (NDC)
//
// See AGENTS.md's "Architecture: FSR2 temporal upscaling" for the overall pass design.
#version 150

void main() {
    // TODO(FSR2): not implemented.
}
