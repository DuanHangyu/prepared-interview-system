package system.assessment.defense.prepared.storage;

import com.aliyun.oss.*;
import com.aliyun.oss.common.comm.Protocol;
import com.aliyun.oss.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.media.PreparedWaveAudio;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.security.*;
import java.time.Clock;
import java.util.*;

/** Internal private media boundary. Caller must authorize assessment/question/asset before use.
 * No delete or overwrite API is exposed; writes only count after actual remote-byte verification.
 */
@Component
public class PreparedPrivateOssStore {
    public record Broadcast(int assessmentId,String questionId,String assetId) {
        public Broadcast {if(assessmentId<1)throw new IllegalArgumentException("播报归属无效");
            PreparedJobInput.uuid(questionId);PreparedJobInput.uuid(assetId);}
        public String key(String deployment){
            if(deployment==null || !deployment.matches("prepared-[a-z0-9][a-z0-9-]{0,47}") || deployment.endsWith("-"))
                throw new IllegalArgumentException("播报命名空间无效");
            return "prepared/"+deployment+"/assessments/"+assessmentId+"/questions/"+questionId+"/audio/"+assetId+".wav";
        }
    }
    public record Stored(Broadcast scope,String objectKey,PreparedWaveAudio.Verified proof) {}
    /** A chunk is not independently decodable media. This proof binds bytes, NOT whole-answer validity. */
    public record AnswerChunk(int assessmentId,int studentId,String attemptId,String answerId,String segmentId,
                              int index,int byteSize,String sha256,String mimeType) {
        public AnswerChunk {
            if(assessmentId<1||studentId<1||index<0||index>=10000||byteSize<1||byteSize>2097152
                    ||sha256==null||!sha256.matches("[0-9a-f]{64}")||mimeType==null
                    ||!Set.of("audio/webm;codecs=opus","audio/webm","audio/ogg;codecs=opus","audio/ogg","audio/mp4").contains(mimeType))
                throw new IllegalArgumentException("录音分片归属或大小无效");
            PreparedJobInput.uuid(attemptId);PreparedJobInput.uuid(answerId);PreparedJobInput.uuid(segmentId);
        }
        public String key(String deployment) {
            if(deployment==null||!deployment.matches("prepared-[a-z0-9][a-z0-9-]{0,47}")||deployment.endsWith("-"))
                throw new IllegalArgumentException("录音命名空间无效");
            return "prepared/"+deployment+"/students/"+studentId+"/assessments/"+assessmentId+"/attempts/"+attemptId
                    +"/answers/"+answerId+"/segments/"+segmentId+"/chunks/"+index+".bin";
        }
    }
    public record StoredChunk(AnswerChunk scope,String objectKey) {}
    /** One object transports up to 32 independently digest-bound original chunks. */
    public record AnswerBundle(String id,List<AnswerChunk> chunks,int byteSize,String sha256) {
        public AnswerBundle {
            if(id==null||!id.matches("[a-f0-9]{64}")||chunks==null||chunks.isEmpty()||chunks.size()>32
                    ||byteSize<1||byteSize>8388608||sha256==null||!sha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("原录音批次范围无效");
            chunks=List.copyOf(chunks);var first=chunks.getFirst();long total=0;int previous=-1;
            for(var chunk:chunks) {
                if(chunk.assessmentId()!=first.assessmentId()||chunk.studentId()!=first.studentId()||!chunk.attemptId().equals(first.attemptId())
                        ||!chunk.answerId().equals(first.answerId())||!chunk.segmentId().equals(first.segmentId())||!chunk.mimeType().equals(first.mimeType())
                        ||chunk.index()<=previous)throw new IllegalArgumentException("原录音批次范围无效");
                previous=chunk.index();total+=chunk.byteSize();
            }
            if(total!=byteSize)throw new IllegalArgumentException("原录音批次大小无效");
        }
        public String key(String deployment) {
            var first=chunks.getFirst();String original=first.key(deployment);
            return original.substring(0,original.indexOf("/segments/"))+"/bundles/"+id+".bin";
        }
    }
    public record StoredBundle(AnswerBundle scope,String objectKey) {}
    /** Canonical decoded derivative remains bound to the immutable original end manifest. */
    public record AnswerFile(int assessmentId,int studentId,String attemptId,String answerId,String manifestSha256) {
        public AnswerFile{if(assessmentId<1||studentId<1||manifestSha256==null||!manifestSha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("整题录音归属无效");PreparedJobInput.uuid(attemptId);PreparedJobInput.uuid(answerId);}
        public String key(String deployment){return new AnswerChunk(assessmentId,studentId,attemptId,answerId,answerId,0,1,manifestSha256,"audio/webm").key(deployment).split("/segments/",2)[0]+"/media/"+manifestSha256+".wav";}
    }
    public record StoredAnswer(AnswerFile scope,String objectKey,PreparedWaveAudio.Verified proof) {}
    public record Preview(String url,String expiresAt) {
        @Override public String toString(){return "Preview[private signed URL redacted, expiresAt="+expiresAt+"]";}
    }
    @FunctionalInterface public interface Factory {OSS open(PreparedStoragePolicy.Snapshot snapshot,String id,String secret);}
    private final PreparedStoragePolicy policy;private final Factory factory;private final Clock clock;
    @Autowired public PreparedPrivateOssStore(PreparedStoragePolicy policy){this(policy,
            (p,id,secret)->OSSClientBuilder.create().endpoint(p.endpoint())
                    .credentialsProvider(new com.aliyun.oss.common.auth.DefaultCredentialProvider(id,secret))
                    .clientConfiguration(clientConfiguration()).region("cn-beijing").build(),Clock.systemUTC());}
    public PreparedPrivateOssStore(PreparedStoragePolicy policy,Factory factory,Clock clock){this.policy=policy;this.factory=factory;this.clock=clock;}
    public static ClientBuilderConfiguration clientConfiguration(){
        var c=new ClientBuilderConfiguration();c.setProtocol(Protocol.HTTPS);c.setMaxErrorRetry(0);c.setRedirectEnable(false);
        c.setSignatureVersion(com.aliyun.oss.common.comm.SignVersion.V4);
        c.setVerifySSLEnable(true);c.setConnectionTimeout(5000);c.setSocketTimeout(30000);c.setConnectionRequestTimeout(5000);
        c.setRequestTimeoutEnabled(true);c.setRequestTimeout(90000);c.setMaxConnections(4);c.setCrcCheckEnabled(true);
        c.setLogConnectionPoolStats(false);return c;
    }
    public Stored putBroadcast(Broadcast scope,Path source) throws IOException {
        OSS client=null;
        try {
            var p=policy.snapshot();var proof=PreparedWaveAudio.inspect(source);String key=scope.key(p.deploymentId());
            client=factory.open(p,policy.accessKeyId(),policy.accessKeySecret());privateBucket(client,p);
            if(!client.doesObjectExist(p.bucket(),key)) {
                var metadata=new ObjectMetadata();metadata.setContentLength(proof.byteSize());metadata.setContentType("audio/wav");
                metadata.setCacheControl("private, no-store");metadata.setObjectAcl(CannedAccessControlList.Private);
                metadata.setServerSideEncryption("AES256");metadata.setContentMD5(md5(source));
                metadata.addUserMetadata("prepared-sha256",proof.sha256());metadata.setHeader("x-oss-forbid-overwrite","true");
                try(var input=Files.newInputStream(source,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                    try {client.putObject(new PutObjectRequest(p.bucket(),key,input,metadata));}
                    catch(OSSException e){if(!"FileAlreadyExists".equals(e.getErrorCode()))throw e;}
                }
            }
            verify(client,p,key,proof);return new Stored(scope,key,proof);
        }catch(IOException|RuntimeException error){throw failure();}
        finally {close(client);}
    }
    /** Internal authorized streaming write; never overwrites, deletes, or trusts object metadata as a digest. */
    public StoredChunk putAnswerChunk(AnswerChunk scope,Path source) throws IOException {
        OSS client=null;
        try {
            var p=policy.snapshot();String key=scope.key(p.deploymentId());
            if(source==null||!source.isAbsolute()||!source.normalize().equals(source)||!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS))throw chunkFailure();
            try(var input=Files.newInputStream(source,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                verifyChunkBytes(input,scope);
            }
            client=factory.open(p,policy.accessKeyId(),policy.accessKeySecret());privateBucket(client,p);
            if(!client.doesObjectExist(p.bucket(),key)) {
                var metadata=new ObjectMetadata();metadata.setContentLength(scope.byteSize());metadata.setContentType("application/octet-stream");
                metadata.setCacheControl("private, no-store");metadata.setObjectAcl(CannedAccessControlList.Private);
                metadata.setServerSideEncryption("AES256");metadata.setContentMD5(md5(source));
                metadata.addUserMetadata("prepared-sha256",scope.sha256());metadata.addUserMetadata("prepared-mime",scope.mimeType());
                metadata.setHeader("x-oss-forbid-overwrite","true");
                try(var input=Files.newInputStream(source,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                    try{client.putObject(new PutObjectRequest(p.bucket(),key,input,metadata));}
                    catch(OSSException failure){if(!"FileAlreadyExists".equals(failure.getErrorCode()))throw failure;}
                }
            }
            var acl=client.getObjectAcl(p.bucket(),key);if(acl==null||acl.getPermission()!=ObjectPermission.Private)throw chunkFailure();
            try(var object=client.getObject(p.bucket(),key)) {
                if(object==null||object.getObjectMetadata()==null||object.getObjectMetadata().getContentLength()!=scope.byteSize()||object.getObjectContent()==null)throw chunkFailure();
                verifyChunkBytes(object.getObjectContent(),scope);
            }
            return new StoredChunk(scope,key);
        }catch(IOException|RuntimeException failure){throw chunkFailure();}finally{close(client);}
    }
    private static void verifyChunkBytes(InputStream input,AnswerChunk scope)throws IOException {
        var hash=digest("SHA-256");byte[] buffer=new byte[8192];long count=0;int read;
        while((read=input.read(buffer))!=-1){count+=read;if(count>scope.byteSize())throw chunkFailure();hash.update(buffer,0,read);}
        if(count!=scope.byteSize()||!HexFormat.of().formatHex(hash.digest()).equals(scope.sha256()))throw chunkFailure();
    }
    private static IOException chunkFailure(){return new IOException("私有录音分片存储或实际字节核验失败，未确认保存");}
    /** No aggregate heap buffer, remote metadata proof, overwrite or automatic SDK write retry. */
    public StoredBundle putAnswerBundle(AnswerBundle scope,Path source)throws IOException {
        OSS client=null;
        try {
            var p=policy.snapshot();String key=scope.key(p.deploymentId());
            if(source==null||!source.isAbsolute()||!source.normalize().equals(source)||!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS))throw chunkFailure();
            try(var input=Files.newInputStream(source,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){verifyAnswerBundleBytes(input,scope);}
            client=factory.open(p,policy.accessKeyId(),policy.accessKeySecret());privateBucket(client,p);
            if(!client.doesObjectExist(p.bucket(),key)) {
                var metadata=new ObjectMetadata();metadata.setContentLength(scope.byteSize());metadata.setContentType("application/octet-stream");
                metadata.setCacheControl("private, no-store");metadata.setObjectAcl(CannedAccessControlList.Private);
                metadata.setServerSideEncryption("AES256");metadata.setContentMD5(md5(source));metadata.addUserMetadata("prepared-sha256",scope.sha256());
                metadata.setHeader("x-oss-forbid-overwrite","true");
                try(var input=Files.newInputStream(source,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
                    try{client.putObject(new PutObjectRequest(p.bucket(),key,input,metadata));}
                    catch(OSSException error){if(!"FileAlreadyExists".equals(error.getErrorCode()))throw error;}
                }
            }
            var acl=client.getObjectAcl(p.bucket(),key);if(acl==null||acl.getPermission()!=ObjectPermission.Private)throw chunkFailure();
            try(var object=client.getObject(p.bucket(),key)) {
                if(object==null||object.getObjectMetadata()==null||object.getObjectMetadata().getContentLength()!=scope.byteSize()||object.getObjectContent()==null)throw chunkFailure();
                verifyAnswerBundleBytes(object.getObjectContent(),scope);
            }
            return new StoredBundle(scope,key);
        }catch(IOException|RuntimeException error){throw chunkFailure();}finally{close(client);}
    }
    /** Verify concatenation and every original boundary, not merely a batch hash or ETag. */
    public static void verifyAnswerBundleBytes(InputStream input,AnswerBundle scope)throws IOException {
        var whole=digest("SHA-256");byte[] buffer=new byte[8192];
        for(var chunk:scope.chunks()) {
            var individual=digest("SHA-256");int remaining=chunk.byteSize();
            while(remaining>0) {
                int read=input.read(buffer,0,Math.min(buffer.length,remaining));if(read<1)throw chunkFailure();
                remaining-=read;individual.update(buffer,0,read);whole.update(buffer,0,read);
            }
            if(!HexFormat.of().formatHex(individual.digest()).equals(chunk.sha256()))throw chunkFailure();
        }
        if(input.read()!=-1||!HexFormat.of().formatHex(whole.digest()).equals(scope.sha256()))throw chunkFailure();
    }
    /** Authorized internal whole-object reads, never guess a bundle's legacy single-chunk key. */
    public void downloadAnswerBundle(StoredBundle stored,Path target)throws IOException {
        var p=policy.snapshot();if(stored==null||!stored.scope().key(p.deploymentId()).equals(stored.objectKey()))throw chunkFailure();
        downloadBytes(stored.objectKey(),stored.scope().byteSize(),target,
                input->verifyAnswerBundleBytes(input,stored.scope()));
    }
    public void downloadAnswerChunk(StoredChunk stored,Path target)throws IOException {
        var p=policy.snapshot();if(stored==null||!stored.scope().key(p.deploymentId()).equals(stored.objectKey()))throw chunkFailure();
        downloadBytes(stored.objectKey(),stored.scope().byteSize(),target,input->verifyChunkBytes(input,stored.scope()));
    }
    @FunctionalInterface private interface ByteCheck{void verify(InputStream stream)throws IOException;}
    private void downloadBytes(String key,long size,Path target,ByteCheck check)throws IOException {
        OSS client=null;boolean created=false;
        try{
            if(target==null||!target.isAbsolute()||!target.normalize().equals(target))throw chunkFailure();
            for(var parent=target.getParent();parent!=null;parent=parent.getParent())if(!Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS))throw chunkFailure();
            var p=policy.snapshot();client=factory.open(p,policy.accessKeyId(),policy.accessKeySecret());privateBucket(client,p);
            var acl=client.getObjectAcl(p.bucket(),key);if(acl==null||acl.getPermission()!=ObjectPermission.Private)throw chunkFailure();
            try(var object=client.getObject(p.bucket(),key)){
                if(object==null||object.getObjectMetadata()==null||object.getObjectMetadata().getContentLength()!=size||object.getObjectContent()==null)throw chunkFailure();
                try(var out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
                    created=true;Files.setPosixFilePermissions(target,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                    var in=object.getObjectContent();byte[] buffer=new byte[8192];long count=0;int read;
                    while((read=in.read(buffer))!=-1){count+=read;if(count>size)throw chunkFailure();out.write(buffer,0,read);}if(count!=size)throw chunkFailure();
                }
            }
            try(var in=Files.newInputStream(target,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){check.verify(in);}
        }catch(IOException|RuntimeException failure){if(created)try{Files.deleteIfExists(target);}catch(IOException ignored){/* Only our new invalid download. */}throw chunkFailure();}
        finally{close(client);}
    }
    /** Real private canonical output readback is required before any whole-answer receipt. */
    public StoredAnswer putAnswer(AnswerFile scope,Path source)throws IOException {
        OSS client=null;
        try{
            var p=policy.snapshot();var proof=PreparedWaveAudio.inspect(source);String key=scope.key(p.deploymentId());
            client=factory.open(p,policy.accessKeyId(),policy.accessKeySecret());privateBucket(client,p);
            if(!client.doesObjectExist(p.bucket(),key)){
                var m=new ObjectMetadata();m.setContentLength(proof.byteSize());m.setContentType("audio/wav");m.setCacheControl("private, no-store");
                m.setObjectAcl(CannedAccessControlList.Private);m.setServerSideEncryption("AES256");m.setContentMD5(md5(source));m.setHeader("x-oss-forbid-overwrite","true");
                try(var in=Files.newInputStream(source,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
                    try{client.putObject(new PutObjectRequest(p.bucket(),key,in,m));}catch(OSSException exists){if(!"FileAlreadyExists".equals(exists.getErrorCode()))throw exists;}
                }
            }
            verify(client,p,key,proof);return new StoredAnswer(scope,key,proof);
        }catch(IOException|RuntimeException failure){throw chunkFailure();}finally{close(client);}
    }
    /** Re-checks privacy and content before issuing a five-minute URL for this exact object.
     * The URL is intentionally returned to the authorized browser, never to logs or audit data. */
    public Preview preview(Stored stored) throws IOException {
        OSS client=null;
        try {
            var p=policy.snapshot();String key=stored.scope().key(p.deploymentId());
            if(!key.equals(stored.objectKey()) || stored.proof()==null)throw failure();
            client=factory.open(p,policy.accessKeyId(),policy.accessKeySecret());privateBucket(client,p);verify(client,p,key,stored.proof());
            var expires=clock.instant().plusSeconds(300);var request=new GeneratePresignedUrlRequest(p.bucket(),key,HttpMethod.GET);
            request.setExpiration(Date.from(expires));var headers=new ResponseHeaderOverrides();
            // OSS rejects response-content-type on signed GET (0017-00000902).
            // WAV MIME is set when writing the verified object, not overridden here.
            headers.setCacheControl("private, no-store");headers.setContentDisposition("inline");request.setResponseHeaders(headers);
            URI url=client.generatePresignedUrl(request).toURI();
            if(!"https".equals(url.getScheme()) || !(p.bucket()+".oss-cn-beijing.aliyuncs.com").equals(url.getHost())
                    || url.getPort()!=-1 || url.getUserInfo()!=null || url.getFragment()!=null || !("/"+key).equals(url.getRawPath())
                    || url.getRawQuery()==null || url.toString().length()>4096)throw failure();
            return new Preview(url.toString(),expires.toString());
        }catch(Exception error){throw failure();}finally {close(client);}
    }
    /** Worker-only original file capability. Rechecks private ACL and actual bytes before signing.
     * Its 24h fetch window is NOT a recording/answering/upload deadline or student playback URL. */
    public Preview asrInput(StoredAnswer stored)throws IOException {
        OSS client=null;
        try{
            var p=policy.snapshot();if(stored==null||stored.proof()==null||stored.proof().channels()!=1||stored.proof().sampleRate()!=16000)throw chunkFailure();
            String key=stored.scope().key(p.deploymentId());if(!key.equals(stored.objectKey()))throw chunkFailure();
            client=factory.open(p,policy.accessKeyId(),policy.accessKeySecret());privateBucket(client,p);verify(client,p,key,stored.proof());
            var expires=clock.instant().plusSeconds(86400);var request=new GeneratePresignedUrlRequest(p.bucket(),key,HttpMethod.GET);request.setExpiration(Date.from(expires));
            var headers=new ResponseHeaderOverrides();headers.setCacheControl("private, no-store");request.setResponseHeaders(headers);
            URI url=client.generatePresignedUrl(request).toURI();
            if(!"https".equals(url.getScheme())||!(p.bucket()+".oss-cn-beijing.aliyuncs.com").equals(url.getHost())||url.getPort()!=-1||url.getUserInfo()!=null
                ||url.getFragment()!=null||!("/"+key).equals(url.getRawPath())||url.getRawQuery()==null||url.toString().length()>4096)throw chunkFailure();
            return new Preview(url.toString(),expires.toString());
        }catch(Exception error){throw chunkFailure();}finally{close(client);}
    }
    /** Teacher-only short capability; caller must authorize BOTH before IO and before delivery.
     * This is the canonical verified original answer, never a partial chunk or ASR result URL. */
    public Preview previewAnswer(StoredAnswer stored)throws IOException {
        OSS client=null;
        try{
            var p=policy.snapshot();if(stored==null||stored.scope()==null||stored.proof()==null||stored.proof().channels()!=1||stored.proof().sampleRate()!=16000||stored.proof().durationMs()<1)throw chunkFailure();
            String key=stored.scope().key(p.deploymentId());if(!key.equals(stored.objectKey()))throw chunkFailure();
            client=factory.open(p,policy.accessKeyId(),policy.accessKeySecret());privateBucket(client,p);verify(client,p,key,stored.proof());
            var expires=clock.instant().plusSeconds(300);var request=new GeneratePresignedUrlRequest(p.bucket(),key,HttpMethod.GET);request.setExpiration(Date.from(expires));
            var headers=new ResponseHeaderOverrides();headers.setCacheControl("private, no-store");headers.setContentDisposition("inline");request.setResponseHeaders(headers);
            URI url=client.generatePresignedUrl(request).toURI();
            if(!"https".equals(url.getScheme())||!(p.bucket()+".oss-cn-beijing.aliyuncs.com").equals(url.getHost())||url.getPort()!=-1||url.getUserInfo()!=null
                ||url.getFragment()!=null||!("/"+key).equals(url.getRawPath())||url.getRawQuery()==null||url.toString().length()>4096)throw chunkFailure();
            return new Preview(url.toString(),expires.toString());
        }catch(Exception error){throw chunkFailure();}finally{close(client);}
    }
    /** Worker-only private read to a new owned file; no whole recording is buffered in memory. */
    public PreparedWaveAudio.Verified download(Stored stored,Path target) throws IOException {
        OSS client=null;boolean created=false;
        try{
            if(target==null || !target.isAbsolute() || !target.normalize().equals(target) || !Files.isDirectory(target.getParent(),LinkOption.NOFOLLOW_LINKS))throw failure();
            for(Path ancestor=target.getParent();ancestor!=null;ancestor=ancestor.getParent())
                if(!Files.isDirectory(ancestor,LinkOption.NOFOLLOW_LINKS))throw failure();
            var p=policy.snapshot();String key=stored.scope().key(p.deploymentId());var proof=stored.proof();
            if(!key.equals(stored.objectKey()) || proof==null || proof.byteSize()<46 || proof.byteSize()>67108864)throw failure();
            client=factory.open(p,policy.accessKeyId(),policy.accessKeySecret());privateBucket(client,p);
            var acl=client.getObjectAcl(p.bucket(),key);if(acl==null || acl.getPermission()!=ObjectPermission.Private)throw failure();
            try(var object=client.getObject(p.bucket(),key)){
                if(object==null || object.getObjectMetadata()==null || object.getObjectMetadata().getContentLength()!=proof.byteSize() || object.getObjectContent()==null)throw failure();
                try(var output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
                    created=true;byte[] buffer=new byte[8192];long count=0;int read;var input=object.getObjectContent();
                    while((read=input.read(buffer))!=-1){count+=read;if(count>proof.byteSize())throw failure();output.write(buffer,0,read);}
                    if(count!=proof.byteSize())throw failure();
                }
            }
            var verified=PreparedWaveAudio.inspect(target);if(!verified.equals(proof))throw failure();return verified;
        }catch(IOException|RuntimeException error){if(created)try{Files.deleteIfExists(target);}catch(IOException ignored){ /* Own invalid artifact only. */ }throw failure();}
        finally{close(client);}
    }
    private void privateBucket(OSS client,PreparedStoragePolicy.Snapshot p) throws IOException {
        var acl=client.getBucketAcl(p.bucket());var versioning=client.getBucketVersioning(p.bucket());var status=client.getBucketPolicyStatus(p.bucket());
        // The real OSS SDK returns a null status when the never-versioned response omits Status.
        // A missing response or any explicit status (including Off) is not that privacy evidence.
        if(acl==null || acl.getCannedACL()!=CannedAccessControlList.Private || versioning==null
                || versioning.getStatus()!=null || status==null || status.isPublic())throw failure();
    }
    private void verify(OSS client,PreparedStoragePolicy.Snapshot p,String key,PreparedWaveAudio.Verified proof) throws IOException {
        if(proof.byteSize()<46 || proof.byteSize()>67108864 || proof.sha256()==null || !proof.sha256().matches("[0-9a-f]{64}"))throw failure();
        var acl=client.getObjectAcl(p.bucket(),key);if(acl==null || acl.getPermission()!=ObjectPermission.Private)throw failure();
        try(var object=client.getObject(p.bucket(),key)) {
            if(object==null || object.getObjectMetadata()==null || object.getObjectMetadata().getContentLength()!=proof.byteSize()
                    || object.getObjectContent()==null)throw failure();
            var digest=digest("SHA-256");byte[] buffer=new byte[8192];long count=0;int read;var input=object.getObjectContent();
            while((read=input.read(buffer))!=-1){count+=read;if(count>proof.byteSize())throw failure();digest.update(buffer,0,read);}
            if(count!=proof.byteSize() || !HexFormat.of().formatHex(digest.digest()).equals(proof.sha256()))throw failure();
        }
    }
    private static String md5(Path source) throws IOException {
        var digest=digest("MD5");try(var in=Files.newInputStream(source,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer=new byte[8192];long count=0;int read;
            while((read=in.read(buffer))!=-1){count+=read;if(count>67108864)throw failure();digest.update(buffer,0,read);}
        }return Base64.getEncoder().encodeToString(digest.digest());
    }
    private static MessageDigest digest(String algorithm){try{return MessageDigest.getInstance(algorithm);}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("摘要算法不可用");}}
    private static void close(OSS client){if(client!=null)try{client.shutdown();}catch(RuntimeException ignored){ /* Never expose SDK credentials/URLs. */ }}
    private static IOException failure(){return new IOException("私有播报存储或完整内容核验失败，未确认保存");}
}
