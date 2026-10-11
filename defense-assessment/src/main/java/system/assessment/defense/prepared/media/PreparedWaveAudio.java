package system.assessment.defense.prepared.media;

import java.nio.file.Path;
import java.io.IOException;
import java.io.InputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

public final class PreparedWaveAudio {
    private PreparedWaveAudio() {}
    public record Verified(long byteSize,String sha256,long durationMs,int sampleRate,int channels,long dataOffset,long dataSize) {}
    private static final long MAX_BYTES=67108864,MAX_DURATION_MS=1800000;
    private static final Set<Integer> RATES=Set.of(8000,16000,22050,24000,32000,44100,48000);
    /** Full-file streaming validation for prepared PCM16 WAV only. Not a decoder for
     * MediaRecorder/WebM chunks, which must retain recorder-segment boundaries. */
    public static Verified inspect(Path path) throws IOException {
        if (path==null || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw invalid();
        long size=Files.size(path);
        if (size<46 || size>MAX_BYTES) throw invalid();
        MessageDigest digest;
        try { digest=MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("无法计算音频摘要"); }
        try (var raw=Files.newInputStream(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
             var input=new DataInputStream(new DigestInputStream(raw,digest))) {
            if (!tag(input).equals("RIFF") || unsignedInt(input)!=size-8 || !tag(input).equals("WAVE")) throw invalid();
            long remaining=size-12, dataOffset=0,dataSize=0;
            int rate=0,channels=0,alignment=0,chunks=0;boolean format=false,data=false;
            byte[] buffer=new byte[8192];
            while (remaining>0) {
                if (remaining<8 || ++chunks>256) throw invalid();
                String chunk=tag(input);long length=unsignedInt(input);remaining-=8;
                long padded=length+(length&1);
                if (padded>remaining) throw invalid();
                if (chunk.equals("fmt ")) {
                    if (format || data || (length!=16 && length!=18)) throw invalid();
                    int encoding=unsignedShort(input);channels=unsignedShort(input);long sampleRate=unsignedInt(input),byteRate=unsignedInt(input);
                    alignment=unsignedShort(input);int bits=unsignedShort(input);
                    if (encoding!=1 || channels<1 || channels>2 || sampleRate>Integer.MAX_VALUE || !RATES.contains((int)sampleRate)
                            || bits!=16 || alignment!=channels*2 || byteRate!=sampleRate*alignment) throw invalid();
                    if (length==18 && unsignedShort(input)!=0) throw invalid();
                    rate=(int)sampleRate;format=true;
                } else {
                    if (chunk.equals("data")) {
                        if (!format || data || length==0 || length%alignment!=0) throw invalid();
                        dataOffset=size-remaining;dataSize=length;data=true;
                    }
                    drain(input,length,buffer);
                }
                if ((length&1)!=0) input.readUnsignedByte();
                remaining-=padded;
            }
            if (!format || !data || input.read()!=-1) throw invalid();
            long frames=dataSize/alignment;
            long duration=(frames*1000+rate-1)/rate;
            if (duration<1 || duration>MAX_DURATION_MS) throw invalid();
            return new Verified(size,HexFormat.of().formatHex(digest.digest()),duration,rate,channels,dataOffset,dataSize);
        }
    }
    private static String tag(DataInputStream in) throws IOException {
        byte[] bytes=new byte[4];in.readFully(bytes);return new String(bytes,java.nio.charset.StandardCharsets.US_ASCII);
    }
    private static long unsignedInt(DataInputStream in) throws IOException {return Integer.toUnsignedLong(Integer.reverseBytes(in.readInt()));}
    private static int unsignedShort(DataInputStream in) throws IOException {return Short.toUnsignedInt(Short.reverseBytes(in.readShort()));}
    private static void drain(InputStream in,long count,byte[] buffer) throws IOException {
        while (count>0) {
            int read=in.read(buffer,0,(int)Math.min(buffer.length,count));
            if (read<1) throw invalid();count-=read;
        }
    }
    private static IOException invalid(){return new IOException("播报文件格式、大小或完整性不符合要求");}
}
