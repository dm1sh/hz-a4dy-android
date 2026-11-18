// SPDX-License-Identifier: MIT
// Original offline probe stub. No vendor implementation.
package android.graphics; public class Bitmap {
 public final int width,height; public final int[] pixels;
 public Bitmap(int w,int h,int[] p) { width=w;height=h;pixels=p; }
 public int getWidth(){return width;} public int getHeight(){return height;}
 public void getPixels(int[] dst,int off,int stride,int x,int y,int w,int h) { for(int row=0;row<h;row++) System.arraycopy(pixels,(y+row)*width+x,dst,off+row*stride,w); }
}
