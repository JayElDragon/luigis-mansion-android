package org.libsdl.app;

import android.graphics.*;

/** Small GPU fire fields anchored to the two painted bowls, in artwork coordinates. */
final class TorchFire {
    private final RuntimeShader shader;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final String SOURCE =
        "uniform shader scene; uniform float time; uniform float2 anchor; uniform float seed;\n"+
        "float hash(float2 p) { return fract(sin(dot(p,float2(127.1,311.7)))*43758.5453); }\n"+
        "float noise(float2 p) { float2 i=floor(p), f=fract(p); f=f*f*(3.0-2.0*f); return mix(mix(hash(i),hash(i+float2(1,0)),f.x),mix(hash(i+float2(0,1)),hash(i+float2(1,1)),f.x),f.y); }\n"+
        "float fbm(float2 p) { return .57*noise(p)+.28*noise(p*2.03+7.0)+.15*noise(p*4.07+19.0); }\n"+
        "half4 main(float2 pixel) {\n"+
        " half4 original=scene.eval(pixel); float2 q=float2((pixel.x-anchor.x)/29.0,(anchor.y-pixel.y)/66.0);\n"+
        " float y=q.y; float edge=(1.0-smoothstep(.93,1.23,abs(q.x)))*smoothstep(-.02,.06,y)*(1.0-smoothstep(.93,1.22,y));\n"+
        " float cyan=smoothstep(.06,.25,original.b-original.r)*smoothstep(.15,.55,original.g);\n"+
        " half3 bg=original.rgb*(1.0-.88*cyan*edge);\n"+
        " float clock=time+seed; float n=fbm(float2(q.x*3.6+seed,y*4.2-clock*2.9));\n"+
        " float curl=(noise(float2(y*4.5-clock*1.6,seed+8.0))-.5)*(.15+y*.48);\n"+
        " float width=.47*pow(max(0.0,1.0-y),.72);\n"+
        " float field=width-abs(q.x+curl)+(n-.48)*(.13+y*.38);\n"+
        " float body=smoothstep(-.07,.10,field)*smoothstep(-.02,.06,y)*(1.0-smoothstep(.90,1.15,y));\n"+
        " float core=smoothstep(.08,.30,field)*(1.0-smoothstep(.15,.68,y));\n"+
        " float wisps=0.0;\n"+
        " for(int i=0;i<4;i++) { float f=float(i); float age=fract(clock*(.65+f*.07)+f*.27); float py=.20+age*.86; float px=sin(f*4.1+age*6.0+seed)*(.10+age*.17); float2 d=float2((q.x-px)/(.075*(1.0-age)+.015),(y-py)/(.13*(1.0-age)+.025)); wisps+=exp(-dot(d,d)*2.0)*sin(age*3.14159)*.75; }\n"+
        " float intensity=clamp(body+wisps,0.0,1.0);\n"+
        " half3 fire=mix(half3(.03,.40,1.0),half3(.13,.86,1.0),smoothstep(.10,.85,intensity)); fire=mix(fire,half3(.80,1.0,1.0),core);\n"+
        " float halo=exp(-q.x*q.x*2.7-(y-.35)*(y-.35)*3.0)*.13*(.8+.2*n)*edge;\n"+
        " half3 result=mix(bg,fire,intensity*.95)+half3(.0,.45,.75)*halo;\n"+
        " return half4(mix(original.rgb,result,edge),original.a); }";

    TorchFire(Bitmap artwork) {
        shader = new RuntimeShader(SOURCE);
        BitmapShader background = new BitmapShader(artwork,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
        Matrix matrix=new Matrix(); matrix.setScale(1672f/artwork.getWidth(),941f/artwork.getHeight());
        background.setLocalMatrix(matrix);
        shader.setInputShader("scene",background);
        paint.setShader(shader);
    }

    /** dx1/dx2: how far each bowl has slid right on a widescreen menu (0 on a normal screen). */
    void draw(Canvas canvas,float seconds,float dx1,float dx2) {
        shader.setFloatUniform("time",seconds);
        canvas.save(); canvas.translate(dx1,0); drawOne(canvas,1067,620,0); canvas.restore();
        canvas.save(); canvas.translate(dx2,0); drawOne(canvas,1436,639,7.3f); canvas.restore();
    }
    private void drawOne(Canvas canvas,float x,float y,float seed) {
        shader.setFloatUniform("anchor",x,y);
        shader.setFloatUniform("seed",seed);
        canvas.drawRect(x-38,y-84,x+38,y+2,paint);
    }
}
