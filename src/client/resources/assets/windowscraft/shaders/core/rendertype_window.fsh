#version 330

uniform sampler2D Sampler0;

in vec2 texCoord0;

out vec4 fragColor;

void main() {
	vec4 color = texture(Sampler0, texCoord0);

#ifdef ALPHA_CUTOUT
	if (color.a < 0.6) {
		discard;
	}
	color.a = 1.0;
#else
	if (color.a < 0.001) {
		discard;
	}
#endif

#ifdef NO_COLOR
	color = vec4(vec3(0.0), color.a);
#endif

	fragColor = color;
}
