package chat.oldy;
import java.util.Arrays;

/** Local PCM processing; no network, model download or changes to the source file. */
final class AudioDsp {
 static short[] process(short[] source,int rate,int effect,float strength){
  strength=Math.max(0,Math.min(1,strength));if(effect==0||strength==0)return source.clone();
  if(effect==1)return denoise(declick(source,rate,strength),rate,strength);
  if(effect==5)return declick(source,rate,strength);
  short[] out=new short[source.length];double low=0,high=0,previous=0,peak=1;
  double hp=Math.exp(-2*Math.PI*(effect==2?280:70)/rate),lp=1-Math.exp(-2*Math.PI*3200/rate);
  float[] values=new float[source.length];int delay=Math.max(1,(int)(rate*.19));
  for(int i=0;i<source.length;i++){
   double input=source[i]/32768.0,value=input;
   if(effect==2){high=hp*(high+input-previous);previous=input;low+=lp*(high-low);value=Math.tanh(low*(1+3*strength))/(1+strength);}
   if(effect==3)value=input*((1-strength)+strength*Math.sin(2*Math.PI*68*i/rate));
   if(effect==4)value=input+(i>=delay?values[i-delay]*strength*.5:0);
   values[i]=(float)value;peak=Math.max(peak,Math.abs(value)/.97);
  }
  for(int i=0;i<out.length;i++)out[i]=(short)Math.round(values[i]/peak*32767);return out;
 }
 /** Only short, isolated discontinuities. Speech transients that do not immediately
  * return to their previous level are retained. This is not a cough classifier. */
 static short[] declick(short[] source,int rate,float amount){short[] out=source.clone();int max=Math.max(1,Math.min(5,rate/8000));double limit=9500-amount*3500;
  for(int i=8;i<source.length-max-8;i++){double before=(source[i-1]+source[i-2]+source[i-3])/3.0;if(Math.abs(source[i]-before)<limit)continue;
   for(int n=1;n<=max;n++){double after=(source[i+n]+source[i+n+1]+source[i+n+2])/3.0;double slope=Math.max(Math.abs(source[i-1]-source[i-4]),Math.abs(source[i+n+3]-source[i+n]));if(Math.abs(after-before)>Math.max(1000,slope*2)||Math.abs(source[i+n]-before)>limit*.35)continue;
    boolean spike=true;for(int k=0;k<n;k++)if(Math.abs(source[i+k]-(before+(after-before)*(k+1)/(n+1)))<limit*.6)spike=false;if(!spike)continue;
    for(int k=0;k<n;k++){double repaired=source[i-1]+(source[i+n]-source[i-1])*(k+1.0)/(n+1);out[i+k]=(short)Math.round(repaired);}i+=n;break;
   }
  }return out;
 }
 /** User-selected unwanted sound: retain timing and use fades to avoid boundary clicks. */
 static short[] muteRange(short[] source,int rate,int start,int end){short[] out=source.clone();start=Math.max(0,Math.min(source.length,start));end=Math.max(start,Math.min(source.length,end));int fade=Math.min(Math.max(1,rate/100),(end-start)/2);for(int i=start;i<end;i++){double gain=0;if(fade>0&&i-start<fade)gain=.5+.5*Math.cos(Math.PI*(i-start)/fade);else if(fade>0&&end-1-i<fade)gain=.5+.5*Math.cos(Math.PI*(end-1-i)/fade);out[i]=(short)Math.round(source[i]*gain);}return out;}
 static short[] denoise(short[] source,int rate,float amount){
  final int n=1024,hop=256,pad=n/2;int length=source.length+2*pad;
  int frames=(length+n-1)/hop;double[] energy=new double[frames],window=new double[n];
  for(int j=0;j<n;j++)window[j]=.5-.5*Math.cos(2*Math.PI*j/(n-1));
  for(int f=0;f<frames;f++){int start=f*hop-pad;double e=0;int count=0;for(int j=0;j<n;j++){int i=start+j;if(i>=0&&i<source.length){double v=source[i]/32768.0;e+=v*v;count++;}}energy[f]=count<n/2?Double.POSITIVE_INFINITY:e/Math.max(1,count);}
  double[] sorted=energy.clone();Arrays.sort(sorted);double cutoff=sorted[Math.min(sorted.length-1,Math.max(0,(int)(sorted.length*.18)))];
  double[] noise=new double[n],re=new double[n],im=new double[n],gain=new double[n],smooth=new double[n];Arrays.fill(smooth,1);int estimates=0;
  int stride=Math.max(1,frames/500);
  for(int f=0;f<frames;f+=stride)if(energy[f]<=cutoff){load(source,f*hop-pad,window,re,im);fft(re,im,false);for(int k=0;k<n;k++)noise[k]+=re[k]*re[k]+im[k]*im[k];estimates++;}
  for(int k=0;k<n;k++)noise[k]/=Math.max(1,estimates);
  float[] sum=new float[source.length],weight=new float[source.length];
  double floor=.45-.28*amount,factor=.6+amount*1.5;
  for(int f=0;f<frames;f++){
   if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
   int start=f*hop-pad;load(source,start,window,re,im);fft(re,im,false);
   for(int k=0;k<n;k++){double power=re[k]*re[k]+im[k]*im[k];gain[k]=Math.max(floor,Math.sqrt(Math.max(0,power-factor*noise[k])/(power+1e-15)));}
   for(int k=0;k<n;k++){double g=(gain[(k+n-1)%n]+2*gain[k]+gain[(k+1)%n])*.25;smooth[k]=.65*smooth[k]+.35*g;double frequency=Math.min(k,n-k)*(double)rate/n;double rumble=frequency<75?Math.max(.08,frequency/75):1;re[k]*=smooth[k]*rumble;im[k]*=smooth[k]*rumble;}
   fft(re,im,true);for(int j=0;j<n;j++){int at=start+j;if(at<0||at>=source.length)continue;sum[at]+=re[j]*window[j];weight[at]+=window[j]*window[j];}
  }
  short[] result=new short[source.length];for(int i=0;i<result.length;i++)result[i]=(short)Math.round(Math.max(-1,Math.min(1,sum[i]/Math.max(.001,weight[i])))*32767);return result;
 }
 static void load(short[] source,int start,double[] window,double[] re,double[] im){Arrays.fill(im,0);for(int i=0;i<re.length;i++){int at=start+i;re[i]=at>=0&&at<source.length?source[at]/32768.0*window[i]:0;}}
 static void fft(double[] real,double[] imaginary,boolean inverse){int n=real.length;
  for(int i=1,j=0;i<n;i++){int bit=n>>1;for(; (j&bit)!=0;bit>>=1)j^=bit;j^=bit;if(i<j){double t=real[i];real[i]=real[j];real[j]=t;t=imaginary[i];imaginary[i]=imaginary[j];imaginary[j]=t;}}
  for(int len=2;len<=n;len<<=1){double angle=2*Math.PI/len*(inverse?1:-1),wr0=Math.cos(angle),wi0=Math.sin(angle);for(int i=0;i<n;i+=len){double wr=1,wi=0;for(int j=0;j<len/2;j++){int u=i+j,v=u+len/2;double vr=real[v]*wr-imaginary[v]*wi,vi=real[v]*wi+imaginary[v]*wr;real[v]=real[u]-vr;imaginary[v]=imaginary[u]-vi;real[u]+=vr;imaginary[u]+=vi;double next=wr*wr0-wi*wi0;wi=wr*wi0+wi*wr0;wr=next;}}}
  if(inverse)for(int i=0;i<n;i++){real[i]/=n;imaginary[i]/=n;}
 }
}
