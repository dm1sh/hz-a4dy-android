// SPDX-License-Identifier: MIT
// Executes ONLY the vendor's translated M2.a print branch against inert, original stubs.
import java.util.*;
import java.security.MessageDigest;
import app.a4dy.core.*;

public class Probe {
 static String hex(byte[] b) { return java.util.HexFormat.of().formatHex(b); }
 public static void main(String[] args) throws Exception {
  int[][] sizes = {{1,1},{7,3},{8,7},{9,11},{383,24},{640,200},{1678,3},{1728,257},{1678,2374}};
  int cases=0;
  System.out.println("{\"probe\":\"isolated translated vendor bytecode vs original core, black-white fixtures\",\"cases\":[");
  for (int[] size:sizes) for(int paperType:new int[]{0,1,2}) {
   int w=size[0],h=size[1],stride=(w+7)/8; int[] pixels=new int[w*h]; byte[] raster=new byte[stride*h];
   for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
    boolean black=((x*17+y*13+5)%11)<5; pixels[y*w+x]=black?0xff000000:0xffffffff;
    if(black)raster[y*stride+x/8]|=(byte)(0x80 >>> (x%8));
   }
   android.graphics.BitmapFactory.current=new android.graphics.Bitmap(w,h,pixels);
   o2.j writer=new o2.j(); o2.w bridge=new o2.w(); bridge.b=writer; M2.b owner=new M2.b(); owner.f=bridge;
   p3.m call=new p3.m(); call.values.put("paperType",paperType); call.values.put("bytes",new byte[]{1});
   ((Runnable)Class.forName("M2.a").getDeclaredConstructor(M2.b.class,p3.m.class,int.class).newInstance(owner,call,1)).run();
   java.util.List<byte[]> actual=writer.packets;
   java.util.List<byte[]> expected=DyProtocol.INSTANCE.pagePackets(new RasterPage(w,h,raster),paperType==1?Feed.CONTINUOUS:Feed.LABEL);
   if(actual.size()!=expected.size())throw new AssertionError("Packet count "+actual.size());
   for(int i=0;i<actual.size();i++)if(!Arrays.equals(actual.get(i),expected.get(i))) throw new AssertionError("Mismatch "+w+"x"+h+" paperType="+paperType+" packet="+i+" vendor="+hex(actual.get(i))+" original="+hex(expected.get(i)));
   if(cases++>0)System.out.println(",");
   System.out.print("{\"width\":"+w+",\"height\":"+h+",\"paperType\":"+paperType+",\"raster_packet_bytes\":"+actual.get(1).length+",\"raster_packet_sha256\":\""+hex(MessageDigest.getInstance("SHA-256").digest(actual.get(1)))+"\",\"feed\":\""+hex(actual.get(2))+"\",\"exact_match\":true}");
  }
  System.out.println("],\"passed\":"+cases+",\"hardware_test\":false}");
 }
}
