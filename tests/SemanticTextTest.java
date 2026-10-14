import org.telegram.messenger.semantic.SemanticText;
import java.util.*;
public class SemanticTextTest {
 public static void main(String[] args)throws Exception {
  String text="";for(int i=0;i<700;i++)text+="中😀";
  ArrayList<String> chunks=SemanticText.chunks(text);
  StringBuilder reconstructed=new StringBuilder(chunks.get(0));
  for(int i=0;i<chunks.size();i++){
   String s=chunks.get(i);if(s.codePointCount(0,s.length())>150)throw new AssertionError("Chunk exceeds bound");
   if(Character.isLowSurrogate(s.charAt(0))||Character.isHighSurrogate(s.charAt(s.length()-1)))throw new AssertionError("Split emoji");
   if(i>0)reconstructed.append(s.substring(s.offsetByCodePoints(0,20)));
  }
  if(!text.equals(reconstructed.toString()))throw new AssertionError("Lost text during chunking");
  float[] v=new float[768];for(int i=0;i<v.length;i++)v[i]=i+1;
  float[] n=SemanticText.normalize(v);
  if(Math.abs(SemanticText.cosine(n,n)-1)>0.00001)throw new AssertionError("Not normalized");
  if(!Arrays.equals(n,SemanticText.decode(SemanticText.encode(n))))throw new AssertionError("Vector persistence changed values");
  v[5]=Float.NaN;try{SemanticText.normalize(v);throw new AssertionError("Accepted NaN");}catch(IllegalArgumentException expected){}
  if(SemanticText.keyword("Bring HDMI adapter and chargers","HDMI adapter")!=2)throw new AssertionError("Exact search lost");
  if(SemanticText.fingerprint("old").equals(SemanticText.fingerprint("edited")))throw new AssertionError("Revision unchanged");
  System.out.println("PASS unicode chunking, no text loss, vector normalization/persistence, invalid vectors and exact keyword matching");
 }
}
