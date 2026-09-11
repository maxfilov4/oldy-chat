package chat.oldy;
import android.content.Context;import android.graphics.*;import android.view.View;
/** Compact play control with its optical centre aligned to the video's centre. */
final class NeonPlay extends View {
 final Paint p=new Paint(3);final Path triangle=new Path();
 NeonPlay(Context c){super(c);setContentDescription(I18n.t("Воспроизвести видео"));setFocusable(true);}
 protected void onDraw(Canvas c){float x=getWidth()/2f,y=getHeight()/2f,r=Math.min(x,y)*.72f;p.setStyle(Paint.Style.FILL);p.setShader(new RadialGradient(x,y,r*1.38f,new int[]{0x0069f8ef,0x4d69f8ef,0x0069f8ef},new float[]{0,.68f,1},Shader.TileMode.CLAMP));c.drawCircle(x,y,r*1.38f,p);p.setShader(null);p.setColor(0xc2152939);c.drawCircle(x,y,r,p);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(Math.max(1,getResources().getDisplayMetrics().density*1.2f));p.setColor(0xcc9efcf1);c.drawCircle(x,y,r,p);p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);triangle.reset();triangle.moveTo(x-r*.22f,y-r*.38f);triangle.lineTo(x+r*.42f,y);triangle.lineTo(x-r*.22f,y+r*.38f);triangle.close();c.drawPath(triangle,p);}
}
