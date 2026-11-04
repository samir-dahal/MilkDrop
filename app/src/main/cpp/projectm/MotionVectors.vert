precision highp float;
precision highp sampler2D;

layout(location = 0) in vec2 vertex_position;
layout(location = 1) in vec4 vertex_color;

uniform mat4 vertex_transformation;
uniform float length_multiplier;
uniform float minimum_length;
uniform sampler2D warp_coordinates;

out vec4 fragment_color;

void main()
{
    // BeatDrop's ReversePropagatePoint(fx, fys), where fys = 1 - fy.
    vec2 origin = vec2(vertex_position.x, 1.0 - vertex_position.y);
    vec2 endpoint = origin;

    if (gl_VertexID % 2 == 1)
    {
        // The warp pass uses projectM's top-down orthogonal projection. Its UV map stores
        // texture V, whereas ReversePropagatePoint returns 1 - V. Convert the returned
        // value before subtracting the origin, not just the lookup or the final position.
        vec2 uv = texture(warp_coordinates, origin).xy;
        vec2 propagated = vec2(uv.x, 1.0 - uv.y);
        vec2 delta = (propagated - origin) * length_multiplier;
        float trailLength = length(delta);

        if (trailLength <= minimum_length)
        {
            if (trailLength > 0.00000001)
            {
                delta *= minimum_length / trailLength;
            }
            else
            {
                // Preserve BeatDrop's visible minimum trail, including its Y direction.
                delta = vec2(minimum_length, -minimum_length);
            }
        }
        endpoint += delta;
    }

    // Match BeatDrop's WFVERTEX, then its fixed-function projection. PrepareFor2DDrawing()
    // uses OrthoLH(2, -2), which flips Y; projectM's orthogonalProjection does the same.
    // Skipping that projection draws the vector on the opposite row from its UV lookup.
    vec2 vertex = vec2(endpoint.x * 2.0 - 1.0, -(endpoint.y * 2.0 - 1.0));
    gl_Position = vertex_transformation * vec4(vertex, 0.0, 1.0);
    fragment_color = vertex_color;
}
