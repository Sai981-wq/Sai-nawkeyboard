package com.sainaw.mm.board;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeoutException;

/** Incremental PCM16 mono to Ogg Opus. Bitrate set to 64k. */
final class ShanStreamingOpus implements AutoCloseable {
    private static final String MIME = "audio/opus";
    private static final int BIT_RATE = 64_000;
    private static final int MAX_PENDING_BYTES = 6_400;
    private static final int MAX_OUTPUT_BYTES = 8 * 1024 * 1024;
    private static final long FINISH_TIMEOUT_MS = 150;
    interface Codec {
        int dequeueInputBuffer(long waitUs);
        ByteBuffer getInputBuffer(int index);
        void queueInputBuffer(int index, int offset, int size, long pts, int flags);
        int dequeueOutputBuffer(MediaCodec.BufferInfo info, long waitUs);
        ByteBuffer getOutputBuffer(int index);
        MediaFormat getOutputFormat();
        void releaseOutputBuffer(int index, boolean render);
        void stop(); void release();
    }
    interface Muxer {
        int addTrack(MediaFormat format); void start();
        void writeSampleData(int track, ByteBuffer data, MediaCodec.BufferInfo info);
        void stop(); void release();
    }
    interface Clock { long elapsedRealtime(); }
    private final File temp;
    private final Codec codec;
    private final Muxer muxer;
    private final Clock clock;
    private final int rate;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private byte[] pending = new byte[MAX_PENDING_BYTES];
    private int pendingSize;
    private long submittedSamples;
    private int track = -1;
    private boolean muxerStarted, inputEos, outputEos, finishing, closed, finished;

    static ShanStreamingOpus open(Context context, int rate) throws Exception {
        if (Build.VERSION.SDK_INT < 29) throw new UnsupportedOperationException("Ogg muxing requires Android API 29+");
        if (context == null) throw new IllegalArgumentException("Context must not be null");
        if (rate <= 0) throw new IllegalArgumentException("Sample rate must be positive");
        return new ShanStreamingOpus(context, rate);
    }
    private ShanStreamingOpus(Context context, int rate) throws Exception {
        File created=File.createTempFile("shan-stream-opus-", ".ogg", context.getCacheDir());
        Codec c=null; Muxer m=null;
        try { c=createCodec(rate); m=new AndroidMuxer(created.getAbsolutePath()); }
        catch (Exception | Error e) { if(m!=null)try{m.release();}catch(RuntimeException ignored){} if(c!=null){try{c.stop();}catch(RuntimeException ignored){}try{c.release();}catch(RuntimeException ignored){}} created.delete(); throw e; }
        this.temp=created; this.rate=rate; this.codec=c; this.muxer=m; this.clock=SystemClock::elapsedRealtime;
    }
    ShanStreamingOpus(File temp, int rate, Codec codec, Muxer muxer, Clock clock) {
        this.temp=temp; this.rate=rate; this.codec=codec; this.muxer=muxer; this.clock=clock;
    }
    private static Codec createCodec(int rate) throws Exception {
        MediaFormat f=MediaFormat.createAudioFormat(MIME, rate, 1);
        f.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE); f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,8192);
        f.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
        MediaCodec c=MediaCodec.createEncoderByType(MIME);
        try { c.configure(f,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE); c.start(); return new AndroidCodec(c); }
        catch (Exception | Error e) { try { c.release(); } catch (RuntimeException ignored) {} throw e; }
    }
    private static final class AndroidCodec implements Codec {
        final MediaCodec c; AndroidCodec(MediaCodec c){this.c=c;}
        public int dequeueInputBuffer(long w){return c.dequeueInputBuffer(w);} public ByteBuffer getInputBuffer(int i){return c.getInputBuffer(i);}
        public void queueInputBuffer(int i,int o,int s,long p,int f){c.queueInputBuffer(i,o,s,p,f);}
        public int dequeueOutputBuffer(MediaCodec.BufferInfo i,long w){return c.dequeueOutputBuffer(i,w);} public ByteBuffer getOutputBuffer(int i){return c.getOutputBuffer(i);}
        public MediaFormat getOutputFormat(){return c.getOutputFormat();} public void releaseOutputBuffer(int i,boolean r){c.releaseOutputBuffer(i,r);}
        public void stop(){c.stop();} public void release(){c.release();}
    }
    private static final class AndroidMuxer implements Muxer {
        final MediaMuxer m; AndroidMuxer(String path)throws Exception{m=new MediaMuxer(path,MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG);}
        public int addTrack(MediaFormat f){return m.addTrack(f);} public void start(){m.start();}
        public void writeSampleData(int t,ByteBuffer b,MediaCodec.BufferInfo i){m.writeSampleData(t,b,i);} public void stop(){m.stop();} public void release(){m.release();}
    }
    synchronized void write(byte[] data,int offset,int count)throws Exception {
        ensureWritable();
        if(data==null||offset<0||count<0||offset>data.length-count)throw new IllegalArgumentException("Invalid PCM slice");
        if((count&1)!=0)throw new IllegalArgumentException("PCM16 slices must have even byte length"); if(count==0)return;
        drainAvailable(); pumpAvailableInput();
        if(count>MAX_PENDING_BYTES-pendingSize)throw new IllegalStateException("Opus encoder backlog exceeded 200 ms");
        System.arraycopy(data,offset,pending,pendingSize,count); pendingSize+=count;
        pumpAvailableInput(); drainAvailable();
    }
    synchronized byte[] finish()throws Exception {
        ensureWritable(); finishing=true; long deadline=clock.elapsedRealtime()+FINISH_TIMEOUT_MS;
        while(!inputEos||!outputEos){
            if(clock.elapsedRealtime()>=deadline)throw new TimeoutException("Opus finalization exceeded 150 ms");
            pumpAvailableInput(Math.min(1000,Math.max(0,(deadline-clock.elapsedRealtime())*1000)));
            drainAvailable(Math.min(1000,Math.max(0,(deadline-clock.elapsedRealtime())*1000)));
        }
        if(!muxerStarted)throw new IllegalStateException("Encoder produced no Ogg track");
        muxer.stop(); muxerStarted=false; finished=true;
        if(temp.length()<32||temp.length()>MAX_OUTPUT_BYTES)throw new IllegalStateException("Invalid Ogg output size");
        try(FileInputStream in=new FileInputStream(temp); ByteArrayOutputStream out=new ByteArrayOutputStream((int)temp.length())){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()>MAX_OUTPUT_BYTES-n)throw new IllegalStateException("Ogg output too large");out.write(b,0,n);}
            byte[] bytes=out.toByteArray(); if(bytes.length<4||bytes[0]!='O'||bytes[1]!='g'||bytes[2]!='g'||bytes[3]!='S')throw new IllegalStateException("Muxer did not create an Ogg stream"); return bytes;
        } finally {close();}
    }
    private void pumpAvailableInput(){pumpAvailableInput(0);}
    private void pumpAvailableInput(long waitUs){
        if(inputEos||(!finishing&&pendingSize==0))return;
        int index=codec.dequeueInputBuffer(waitUs); if(index<0)return;
        ByteBuffer in=codec.getInputBuffer(index); if(in==null)throw new IllegalStateException("Encoder returned no input buffer"); in.clear();
        int count=Math.min(pendingSize,in.remaining())&~1;
        if(count>0){long pts=submittedSamples*1_000_000L/rate;in.put(pending,0,count);codec.queueInputBuffer(index,0,count,pts,0);submittedSamples+=count/2L;pendingSize-=count;if(pendingSize>0)System.arraycopy(pending,count,pending,0,pendingSize);}
        else if(finishing && pendingSize==0){long pts=submittedSamples*1_000_000L/rate;codec.queueInputBuffer(index,0,0,pts,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEos=true;}
        else {
            codec.queueInputBuffer(index,0,0,submittedSamples*1_000_000L/rate,0);
            throw new IllegalStateException("Encoder input capacity cannot hold a PCM16 sample");
        }
    }
    private void drainAvailable(){drainAvailable(0);}
    private void drainAvailable(long waitUs){
        for(int i=0;i<8;i++){int index=codec.dequeueOutputBuffer(info,waitUs);if(index==MediaCodec.INFO_TRY_AGAIN_LATER)return;
            if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){if(muxerStarted)throw new IllegalStateException("Output format changed twice");track=muxer.addTrack(codec.getOutputFormat());muxer.start();muxerStarted=true;}
            else if(index>=0)try{ByteBuffer out=codec.getOutputBuffer(index);if(out==null)throw new IllegalStateException("Encoder returned no output buffer");
                if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){if(!muxerStarted)throw new IllegalStateException("Sample before output format");out.position(info.offset);out.limit(info.offset+info.size);muxer.writeSampleData(track,out,info);}
                if((info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)outputEos=true;
            }finally{codec.releaseOutputBuffer(index,false);}
        }
    }
    private void ensureWritable(){if(closed||finished)throw new IllegalStateException("Encoder is closed");}
    @Override public synchronized void close(){if(closed)return;closed=true;
        if(muxerStarted)try{muxer.stop();}catch(RuntimeException ignored){} try{muxer.release();}catch(RuntimeException ignored){}
        try{codec.stop();}catch(RuntimeException ignored){} try{codec.release();}catch(RuntimeException ignored){}
        temp.delete(); pending=Arrays.copyOf(pending,0);
    }
}

