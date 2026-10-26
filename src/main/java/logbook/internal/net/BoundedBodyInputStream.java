package logbook.internal.net;

import java.io.*;
import java.util.Objects;

/** Throws on overflow instead of turning an oversized decoded body into a successful prefix. */
final class BoundedBodyInputStream extends FilterInputStream {
    private final long limit;
    private long read;
    private long marked;
    BoundedBodyInputStream(InputStream input,long limit) {
        super(Objects.requireNonNull(input));if(limit<0)throw new IllegalArgumentException("Negative limit");this.limit=limit;
    }
    @Override public int read() throws IOException {
        int value=in.read();if(value<0)return -1;
        if(read>=limit)throw new IOException("Decoded response body exceeds limit");
        read++;return value;
    }
    @Override public int read(byte[] b,int off,int length) throws IOException {
        Objects.checkFromIndexSize(off,length,b.length);if(length==0)return 0;
        if(read>=limit){int value=read();return value<0?-1:1;}
        int count=in.read(b,off,(int)Math.min(length,limit-read));if(count>0)read+=count;return count;
    }
    @Override public long skip(long count) throws IOException {
        if(count<=0)return 0;long skipped=0;byte[] buffer=new byte[(int)Math.min(8192,count)];
        while(skipped<count){int n=read(buffer,0,(int)Math.min(buffer.length,count-skipped));if(n<0)break;skipped+=n;}return skipped;
    }
    @Override public int available() throws IOException{return (int)Math.min(in.available(),Math.max(0,limit-read));}
    @Override public synchronized void mark(int readlimit){in.mark(readlimit);marked=read;}
    @Override public synchronized void reset() throws IOException{in.reset();read=marked;}
}
