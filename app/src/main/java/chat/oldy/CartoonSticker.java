package chat.oldy;
import android.graphics.*;

/** Local illustration filter: edge-preserving smoothing, flat colour and ink contours.
 * It preserves the source geometry; it is not a generative portrait model. */
final class CartoonSticker {
 static Bitmap create(Bitmap source,int strength){int w=source.getWidth(),h=source.getHeight();int[] original=new int[w*h],smooth=new int[w*h],out=new int[w*h];source.getPixels(original,0,w,0,0,w,h);
  for(int y=0;y<h;y++)for(int x=0;x<w;x++){int at=y*w+x,c=original[at];if(Color.alpha(c)<8)continue;float rr=0,gg=0,bb=0,weights=0;int cr=Color.red(c),cg=Color.green(c),cb=Color.blue(c);
   for(int dy=-2;dy<=2;dy++)for(int dx=-2;dx<=2;dx++){int nx=x+dx,ny=y+dy;if(nx<0||ny<0||nx>=w||ny>=h)continue;int p=original[ny*w+nx];if(Color.alpha(p)<8)continue;float dr=Color.red(p)-cr,dg=Color.green(p)-cg,db=Color.blue(p)-cb;float q=1f/(1f+(dr*dr+dg*dg+db*db)/900f+(dx*dx+dy*dy)*.32f);rr+=Color.red(p)*q;gg+=Color.green(p)*q;bb+=Color.blue(p)*q;weights+=q;}smooth[at]=Color.argb(Color.alpha(c),Math.round(rr/weights),Math.round(gg/weights),Math.round(bb/weights));}
  int step=strength==0?24:34;for(int y=0;y<h;y++)for(int x=0;x<w;x++){int at=y*w+x,c=smooth[at];if(Color.alpha(c)<8)continue;int left=smooth[y*w+Math.max(0,x-1)],right=smooth[y*w+Math.min(w-1,x+1)],up=smooth[Math.max(0,y-1)*w+x],down=smooth[Math.min(h-1,y+1)*w+x];float edge=Math.abs(luma(left)-luma(right))+Math.abs(luma(up)-luma(down));float ink=Math.min(.65f,Math.max(0,(edge-25)/110f));float avg=(Color.red(c)+Color.green(c)+Color.blue(c))/3f;int r=channel(Color.red(c),avg,step,ink),g=channel(Color.green(c),avg,step,ink),b=channel(Color.blue(c),avg,step,ink);out[at]=Color.argb(Color.alpha(c),r,g,b);}
  Bitmap result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);result.setPixels(out,0,w,0,0,w,h);return result;
 }
 static float luma(int c){return .299f*Color.red(c)+.587f*Color.green(c)+.114f*Color.blue(c);}static int channel(int c,float avg,int step,float ink){float vivid=avg+(c-avg)*1.16f;float flat=Math.round(vivid/step)*step;return Math.max(0,Math.min(255,Math.round(flat*(1-ink)+24*ink)));}
 static Bitmap frame(Bitmap sticker,int side,int index,int count,int motion){Bitmap out=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(out);Paint p=new Paint(3);float t=(float)(index*Math.PI*2/count),wave=(float)Math.sin(t),stretch=motion==1?1+wave*.025f:1;float offset=motion==2?(float)Math.abs(Math.sin(t))*.035f:wave*.012f;float rotation=motion==0?wave*3.8f:motion==2?wave*2:0;c.translate(side*.5f,side*(.5f-offset));c.rotate(rotation);c.scale(.90f*(2-stretch),.90f*stretch);c.drawBitmap(sticker,null,new RectF(-side/2f,-side/2f,side/2f,side/2f),p);return out;}
}
