// TODO(FSR2): placeholder full-screen-triangle vertex shader -- pairs with fsr2_reconstruct.fsh.
// See that file and AGENTS.md's "Architecture: FSR2 temporal upscaling" for context.
#version 150

in vec4 Position;
out vec2 texCoord;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
    texCoord = Position.xy * 0.5 + 0.5;
}
