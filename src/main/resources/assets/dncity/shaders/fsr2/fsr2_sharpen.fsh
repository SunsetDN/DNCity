// TODO(FSR2): placeholder -- NOT real shader math. RCAS-equivalent sharpening pass, run after
// fsr2_reconstruct.fsh's upscaled output, before the result is copied into Iris's `main` render
// target (see FinalPassRenderer.renderFinalPass's TODO for the exact injection point). Sharpness
// amount should come from FsrConfig.sharpness (0f-1f), mapped to FSR2's own sharpness parameter
// range. See FSR2-LICENSE.txt in this directory for the licensing note this port falls under.
#version 150

uniform sampler2D DiffuseSampler;
uniform float Sharpness;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    // TODO(FSR2): not implemented -- passthrough placeholder only.
    fragColor = texture(DiffuseSampler, texCoord);
}
