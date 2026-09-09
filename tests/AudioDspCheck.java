package chat.oldy;
import java.util.*;
/** Checks observable signal behavior, not internal filter implementation. */
public class AudioDspCheck {
 static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
 static double rms(short[] x,int start,int end){double sum=0;for(int i=start;i<end;i++)sum+=(double)x[i]*x[i];return Math.sqrt(sum/(end-start));}
 static double tone(short[] x,int rate,int frequency,int start,int end){double re=0,im=0;for(int i=start;i<end;i++){re+=x[i]*Math.cos(2*Math.PI*frequency*i/rate);im+=x[i]*Math.sin(2*Math.PI*frequency*i/rate);}return Math.hypot(re,im)/(end-start)*2;}
 public static void main(String[] args){int rate=32000;short[] input=new short[rate*3];Random random=new Random(71);for(int i=0;i<input.length;i++)input[i]=(short)(random.nextGaussian()*700+(i>=rate?6000*Math.sin(2*Math.PI*700*i/rate):0));short[] before=input.clone(),clean=AudioDsp.process(input,rate,1,.7f);check(Arrays.equals(before,input),"Processing changed original audio");check(clean.length==input.length,"Processing changed duration");check(rms(clean,3000,rate-3000)<rms(input,3000,rate-3000)*.75,"Stationary background noise not reduced");check(tone(clean,rate,700,rate+3000,input.length-3000)>tone(input,rate,700,rate+3000,input.length-3000)*.6,"Voice-frequency tone was lost");
  short[] impulse=new short[rate];impulse[0]=12000;short[] echo=AudioDsp.process(impulse,rate,4,1);check(Math.abs(echo[(int)(rate*.19)])>4000,"Echo missing at the selected delay");check(echo.length==impulse.length,"Echo changed clip duration");short[] silence=AudioDsp.process(new short[rate],rate,1,1);check(rms(silence,0,rate)==0,"Noise reduction added sound to silence");check(Arrays.equals(AudioDsp.process(input,rate,0,1),input),"Original mode changed samples");System.out.println("OLDY_AUDIO_DSP_PASS: noise reduced, voice-band tone retained, source unchanged, timing preserved, echo verified");}
}
