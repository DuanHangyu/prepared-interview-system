package system.assessment.defense.prepared.media;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Joins only prevalidated PCM16 broadcast data with equal rate/channels, not recorder segments. */
public final class PreparedWaveComposer {
    private PreparedWaveComposer() {}
    public static PreparedWaveAudio.Verified compose(List<Path> parts,Path target) throws IOException {
        return compose(parts,target,50);
    }
    /** Answer segments must first be independently decoded and fault-free; never accepts raw WebM. */
    public static PreparedWaveAudio.Verified composeAnswer(List<Path> parts,Path target) throws IOException {
        return compose(parts,target,1000);
    }
    private static PreparedWaveAudio.Verified compose(List<Path> parts,Path target,int limit) throws IOException {
        if(parts==null || parts.isEmpty() || parts.size()>limit || target==null || !target.isAbsolute()
                || !target.normalize().equals(target) || Files.exists(target,LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(target.getParent(),LinkOption.NOFOLLOW_LINKS))throw invalid();
        var proofs=new ArrayList<PreparedWaveAudio.Verified>();long bytes=0;int rate=0,channels=0;
        for(Path part:parts) {
            var proof=PreparedWaveAudio.inspect(part);
            if(!proofs.isEmpty() && (rate!=proof.sampleRate() || channels!=proof.channels()))throw invalid();
            rate=proof.sampleRate();channels=proof.channels();bytes+=proof.dataSize();proofs.add(proof);
            if(bytes+44>67108864 || (bytes/(channels*2)*1000+rate-1)/rate>1800000)throw invalid();
        }
        boolean created=false;
        try {
            try(var out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                created=true;var header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
                header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt((int)bytes+36)
                        .put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16)
                        .putShort((short)1).putShort((short)channels).putInt(rate).putInt(rate*channels*2)
                        .putShort((short)(channels*2)).putShort((short)16)
                        .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt((int)bytes);
                out.write(header.array());
                for(int i=0;i<parts.size();i++)copyVerifiedData(parts.get(i),proofs.get(i),out);
            }
            return PreparedWaveAudio.inspect(target);
        }catch(IOException|RuntimeException error){
            if(created)try{Files.deleteIfExists(target);}catch(IOException ignored){ /* Only this new staged output may be cleaned. */ }
            throw invalid();
        }
    }
    private static void copyVerifiedData(Path path,PreparedWaveAudio.Verified proof,OutputStream out) throws IOException {
        MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException("摘要算法不可用");}
        try(var input=new DigestInputStream(Files.newInputStream(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS),digest)) {
            byte[] buffer=new byte[8192];drain(input,null,proof.dataOffset(),buffer);
            drain(input,out,proof.dataSize(),buffer);
            drain(input,null,proof.byteSize()-proof.dataOffset()-proof.dataSize(),buffer);
            if(input.read()!=-1 || !HexFormat.of().formatHex(digest.digest()).equals(proof.sha256()))throw invalid();
        }
    }
    private static void drain(InputStream in,OutputStream out,long length,byte[] buffer) throws IOException {
        while(length>0){int read=in.read(buffer,0,(int)Math.min(length,buffer.length));if(read<1)throw invalid();if(out!=null)out.write(buffer,0,read);length-=read;}
    }
    private static IOException invalid(){return new IOException("整题播报分段格式、完整性或资源范围不符合要求");}
}
