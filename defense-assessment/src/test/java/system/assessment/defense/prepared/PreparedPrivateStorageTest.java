package system.assessment.defense.prepared;

import com.aliyun.oss.*;
import com.aliyun.oss.internal.ResponseParsers;
import com.aliyun.oss.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import system.assessment.defense.prepared.storage.*;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Adapter contract checks with an isolated SDK double, not a real cloud-storage acceptance. */
class PreparedPrivateStorageTest {
    @TempDir Path folder;
    Clock clock=Clock.fixed(Instant.parse("2026-10-04T08:00:00Z"),ZoneOffset.UTC);
    OSS oss;PreparedPrivateOssStore store;byte[] wave;Path source;
    String bucket="prepared-interview-fixture";
    PreparedPrivateOssStore.Broadcast scope=new PreparedPrivateOssStore.Broadcast(4,
            "be1dc9a4-2b1e-4ad3-bb8b-a5fa041c2d9d","0aa711cd-223a-4f06-a7f6-ccad2df141f4");
    MockEnvironment config(){return new MockEnvironment().withProperty("prepared.storage.enabled","true")
            .withProperty("prepared.storage.endpoint","https://oss-cn-beijing.aliyuncs.com")
            .withProperty("prepared.storage.bucket",bucket).withProperty("prepared.storage.access-key-id","fixture-id")
            .withProperty("prepared.storage.access-key-secret","fixture-secret").withProperty("prepared.deployment-id","prepared-fixture");}
    PreparedStoragePolicy policy(){return new PreparedStoragePolicy(config());}
    String key(){return "prepared/prepared-fixture/assessments/4/questions/"+scope.questionId()+"/audio/"+scope.assetId()+".wav";}
    @BeforeEach void setup() throws Exception {
        folder=folder.toRealPath();
        oss=mock(OSS.class);wave=new PreparedSpeechMediaTest().wave();source=folder.resolve("source.wav");Files.write(source,wave);
        var acl=new AccessControlList();acl.setCannedACL(CannedAccessControlList.Private);when(oss.getBucketAcl(bucket)).thenReturn(acl);
        when(oss.getBucketVersioning(bucket)).thenReturn(neverVersioned());
        when(oss.getBucketPolicyStatus(bucket)).thenReturn(new GetBucketPolicyStatusResult());
        var objectAcl=new ObjectAcl();objectAcl.setPermission(ObjectPermission.Private);when(oss.getObjectAcl(bucket,key())).thenReturn(objectAcl);
        when(oss.getObject(bucket,key())).thenAnswer(i->remote(wave));
        store=new PreparedPrivateOssStore(policy(),(p,id,secret)->oss,clock);
    }
    OSSObject remote(byte[] bytes){var result=new OSSObject();var metadata=new ObjectMetadata();metadata.setContentLength(bytes.length);
        result.setObjectMetadata(metadata);result.setObjectContent(new ByteArrayInputStream(bytes));return result;}
    private BucketVersioningConfiguration neverVersioned() throws Exception {
        return ResponseParsers.parseGetBucketVersioning(new ByteArrayInputStream(
                "<VersioningConfiguration xmlns=\"http://doc.oss-cn-hangzhou.aliyuncs.com\"/>".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void disabledOldBucketInsecureOrUnboundedConfigurationNeverOpensAnSdkClient() {
        assertThat(new PreparedStoragePolicy(new MockEnvironment()).availability().enabled()).isFalse();
        for(var entry:Map.of("endpoint","http://oss-cn-beijing.aliyuncs.com","bucket","old-interview-bucket",
                "access-key-id","","access-key-secret","unused-local-test").entrySet()) {
            var p=new PreparedStoragePolicy(config().withProperty("prepared.storage."+entry.getKey(),entry.getValue()));
            assertThat(p.availability().enabled()).as(entry.getKey()).isFalse();
        }
        assertThat(new PreparedStoragePolicy(config().withProperty("prepared.deployment-id","../old")).availability().enabled()).isFalse();
        var client=new PreparedPrivateOssStore(new PreparedStoragePolicy(new MockEnvironment()),(p,id,secret)->{fail("disabled storage must not open");return oss;},clock);
        assertThatThrownBy(()->client.putBroadcast(scope,source)).isInstanceOf(IOException.class);verifyNoInteractions(oss);
    }
    @Test void workerSignsOnlyReverifiedOriginalWholeAnswerForBoundedAsrFetch()throws Exception {
        byte[] asrWave=wave.clone();java.nio.ByteBuffer.wrap(asrWave).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(24,16000).putInt(28,32000);Path asrFile=folder.resolve("asr.wav");Files.write(asrFile,asrWave);
        var answer=new PreparedPrivateOssStore.AnswerFile(4,2,scope.questionId(),scope.assetId(),"a".repeat(64));String key=answer.key("prepared-fixture");
        var acl=new ObjectAcl();acl.setPermission(ObjectPermission.Private);when(oss.getObjectAcl(bucket,key)).thenReturn(acl);when(oss.getObject(bucket,key)).thenAnswer(i->remote(asrWave));
        when(oss.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenAnswer(i->{var r=i.getArgument(0,GeneratePresignedUrlRequest.class);assertThat(r.getKey()).isEqualTo(key);assertThat(r.getExpiration().toInstant()).isEqualTo(clock.instant().plusSeconds(86400));return new URL("https://"+bucket+".oss-cn-beijing.aliyuncs.com/"+key+"?Signature=fixture-private");});
        var media=new PreparedPrivateOssStore.StoredAnswer(answer,key,system.assessment.defense.prepared.media.PreparedWaveAudio.inspect(asrFile));var url=store.asrInput(media);
        assertThat(url.url()).endsWith("Signature=fixture-private");assertThat(url.toString()).doesNotContain("fixture-private");verify(oss,never()).putObject(any(PutObjectRequest.class));
        assertThatThrownBy(()->store.asrInput(new PreparedPrivateOssStore.StoredAnswer(answer,"arbitrary/key",media.proof()))).isInstanceOf(IOException.class);
        byte[] bad=asrWave.clone();bad[100]^=1;when(oss.getObject(bucket,key)).thenAnswer(i->remote(bad));assertThatThrownBy(()->store.asrInput(media)).isInstanceOf(IOException.class);
    }
    @Test void boundedHttpsSdkConfigurationDoesNotAutomaticallyReplayWritesOrRedirectSecrets() {
        var c=PreparedPrivateOssStore.clientConfiguration();
        assertThat(c.getSignatureVersion()).isEqualTo(com.aliyun.oss.common.comm.SignVersion.V4);
        assertThat(c.getProtocol()).isEqualTo(com.aliyun.oss.common.comm.Protocol.HTTPS);assertThat(c.getMaxErrorRetry()).isZero();
        assertThat(c.isRedirectEnable()).isFalse();assertThat(c.isVerifySSLEnable()).isTrue();
        assertThat(c.isRequestTimeoutEnabled()).isTrue();assertThat(c.getRequestTimeout()).isLessThanOrEqualTo(90000);
    }
    /** Real OSS rejects response-content-type on signed GET with 0017-00000902.
     * Uploads already set the verified WAV object's MIME type. Keep the other
     * response privacy overrides and the distinct preview/worker lifetimes.
     * https://help.aliyun.com/zh/oss/user-guide/0017-00000902 */
    @ParameterizedTest @ValueSource(strings={"broadcast","asr","teacher"})
    void signedGetRequestsNeverOverrideContentType(String purpose)throws Exception {
        byte[] bytes=wave.clone();java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(24,16000).putInt(28,32000);
        Path file=folder.resolve("presign-contract.wav");Files.write(file,bytes);
        var proof=system.assessment.defense.prepared.media.PreparedWaveAudio.inspect(file);
        var answer=new PreparedPrivateOssStore.AnswerFile(4,2,scope.questionId(),scope.assetId(),"c".repeat(64));
        String objectKey=purpose.equals("broadcast")?key():answer.key("prepared-fixture");
        var acl=new ObjectAcl();acl.setPermission(ObjectPermission.Private);
        when(oss.getObjectAcl(bucket,objectKey)).thenReturn(acl);when(oss.getObject(bucket,objectKey)).thenAnswer(i->remote(bytes));
        when(oss.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenAnswer(i->{
            var request=i.getArgument(0,GeneratePresignedUrlRequest.class);
            assertThat(request.getMethod()).isEqualTo(HttpMethod.GET);assertThat(request.getBucketName()).isEqualTo(bucket);
            assertThat(request.getKey()).isEqualTo(objectKey);
            assertThat(request.getExpiration().toInstant()).isEqualTo(clock.instant().plusSeconds(purpose.equals("asr")?86400:300));
            assertThat(request.getResponseHeaders().getContentType()).as("OSS forbids response-content-type on signed GET").isNull();
            assertThat(request.getContentType()).isNull();assertThat(request.getResponseHeaders().getCacheControl()).isEqualTo("private, no-store");
            assertThat(request.getResponseHeaders().getContentDisposition()).isEqualTo(purpose.equals("asr")?null:"inline");
            return new URL("https://"+bucket+".oss-cn-beijing.aliyuncs.com/"+objectKey+"?Signature=fixture-only");
        });
        var stored=new PreparedPrivateOssStore.StoredAnswer(answer,objectKey,proof);
        var preview=switch(purpose){case "broadcast"->store.preview(new PreparedPrivateOssStore.Stored(scope,objectKey,proof));case "asr"->store.asrInput(stored);default->store.previewAnswer(stored);};
        assertThat(preview.url()).endsWith("Signature=fixture-only");
        verify(oss,never()).putObject(any(PutObjectRequest.class));verify(oss,never()).deleteObject(anyString(),anyString());
    }
    @Test void teacherOriginalPreviewIsReverifiedAndNeverUsesTheWorkerDayLongCapability()throws Exception {
        byte[] original=wave.clone();java.nio.ByteBuffer.wrap(original).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(24,16000).putInt(28,32000);
        Path file=folder.resolve("teacher-original.wav");Files.write(file,original);
        var scope=new PreparedPrivateOssStore.AnswerFile(4,2,this.scope.questionId(),this.scope.assetId(),"b".repeat(64));String key=scope.key("prepared-fixture");
        var acl=new ObjectAcl();acl.setPermission(ObjectPermission.Private);when(oss.getObjectAcl(bucket,key)).thenReturn(acl);when(oss.getObject(bucket,key)).thenAnswer(i->remote(original));
        var saved=new PreparedPrivateOssStore.StoredAnswer(scope,key,system.assessment.defense.prepared.media.PreparedWaveAudio.inspect(file));
        when(oss.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenAnswer(i->{var r=i.getArgument(0,GeneratePresignedUrlRequest.class);
            assertThat(r.getKey()).isEqualTo(key);assertThat(r.getExpiration().toInstant()).isEqualTo(clock.instant().plusSeconds(300));
            assertThat(r.getResponseHeaders().getCacheControl()).contains("no-store");assertThat(r.getResponseHeaders().getContentDisposition()).isEqualTo("inline");
            return new URL("https://"+bucket+".oss-cn-beijing.aliyuncs.com/"+key+"?Signature=private-teacher");});
        assertThat(store.previewAnswer(saved).expiresAt()).isEqualTo(clock.instant().plusSeconds(300).toString());
        assertThatThrownBy(()->store.previewAnswer(null)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->store.previewAnswer(new PreparedPrivateOssStore.StoredAnswer(scope,"arbitrary-key",saved.proof()))).isInstanceOf(IOException.class);
        byte[] bad=original.clone();bad[100]^=1;when(oss.getObject(bucket,key)).thenAnswer(i->remote(bad));assertThatThrownBy(()->store.previewAnswer(saved)).isInstanceOf(IOException.class);
        when(oss.getObject(bucket,key)).thenAnswer(i->remote(original));acl.setPermission(ObjectPermission.PublicRead);assertThatThrownBy(()->store.previewAnswer(saved)).isInstanceOf(IOException.class);
        acl.setPermission(ObjectPermission.Private);
        assertThatThrownBy(()->store.previewAnswer(new PreparedPrivateOssStore.StoredAnswer(null,key,saved.proof()))).isInstanceOf(IOException.class);
        for(var proof:Arrays.asList(null,new system.assessment.defense.prepared.media.PreparedWaveAudio.Verified(original.length,saved.proof().sha256(),1000,24000,1,44,original.length-44),
                new system.assessment.defense.prepared.media.PreparedWaveAudio.Verified(original.length,saved.proof().sha256(),1000,16000,2,44,original.length-44),
                new system.assessment.defense.prepared.media.PreparedWaveAudio.Verified(original.length,saved.proof().sha256(),0,16000,1,44,original.length-44)))
            assertThatThrownBy(()->store.previewAnswer(new PreparedPrivateOssStore.StoredAnswer(scope,key,proof))).isInstanceOf(IOException.class);
        String base="https://"+bucket+".oss-cn-beijing.aliyuncs.com/"+key;
        for(String raw:List.of(base,base.replace("https:","http:")+"?Signature=secret",base.replace(bucket+".oss-cn-beijing.aliyuncs.com","evil.invalid")+"?Signature=secret",
                base.replace("https://","https://user@")+"?Signature=secret",base.replace(".com/",".com:8443/")+"?Signature=secret",
                base+"?Signature=secret#fragment",base.replace(key,"another.wav")+"?Signature=secret",base+"?Signature="+"x".repeat(4096))) {
            when(oss.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenReturn(new URL(raw));
            assertThatThrownBy(()->store.previewAnswer(saved)).isInstanceOf(IOException.class).hasMessageNotContaining("Signature");
        }
        verify(oss,never()).putObject(any(PutObjectRequest.class));verify(oss,never()).deleteObject(anyString(),anyString());
    }
    @Test void putsPrivateEncryptedMd5CheckedNonOverwritingObjectAndReadsActualBytesBack() throws Exception {
        when(oss.putObject(any(PutObjectRequest.class))).thenAnswer(i->{var p=i.getArgument(0,PutObjectRequest.class);
            assertThat(p.getBucketName()).isEqualTo(bucket);assertThat(p.getKey()).isEqualTo(key());
            assertThat(p.getInputStream().readAllBytes()).isEqualTo(wave);var m=p.getMetadata();
            assertThat(m.getContentLength()).isEqualTo(wave.length);assertThat(m.getContentType()).isEqualTo("audio/wav");
            assertThat(m.getServerSideEncryption()).isEqualTo("AES256");assertThat(m.getRawMetadata()).containsEntry("x-oss-object-acl","private");
            assertThat(m.getContentMD5()).isNotBlank();assertThat(m.getRawMetadata()).containsEntry("x-oss-forbid-overwrite","true");
            assertThat(m.getUserMetadata()).containsKey("prepared-sha256");return new PutObjectResult();});
        var saved=store.putBroadcast(scope,source);assertThat(saved.objectKey()).isEqualTo(key());assertThat(saved.proof().byteSize()).isEqualTo(wave.length);
        verify(oss).getObject(bucket,key());verify(oss).shutdown();
    }
    @Test void identicalExistingObjectReturnsItsProofWithoutOverwriting() throws Exception {
        when(oss.doesObjectExist(bucket,key())).thenReturn(true);var saved=store.putBroadcast(scope,source);
        assertThat(saved.proof().durationMs()).isEqualTo(1000);verify(oss,never()).putObject(any(PutObjectRequest.class));
    }
    @Test void conflictingExistingBytesFailWithoutOverwriteOrDeletion() {
        byte[] other=wave.clone();other[100]^=1;when(oss.doesObjectExist(bucket,key())).thenReturn(true);
        when(oss.getObject(bucket,key())).thenAnswer(i->remote(other));
        assertThatThrownBy(()->store.putBroadcast(scope,source)).isInstanceOf(IOException.class);
        verify(oss,never()).putObject(any(PutObjectRequest.class));verify(oss,never()).deleteObject(anyString(),anyString());
    }
    @Test void racedNoOverwriteResponseCanOnlyRecoverWhenTheActualObjectMatches() throws Exception {
        OSSException exists=new OSSException("sensitive vendor body","FileAlreadyExists","request","host",null,null,null);
        when(oss.putObject(any(PutObjectRequest.class))).thenThrow(exists);
        assertThat(store.putBroadcast(scope,source).proof().byteSize()).isEqualTo(wave.length);
        when(oss.getObject(bucket,key())).thenAnswer(i->remote(new byte[10]));
        assertThatThrownBy(()->store.putBroadcast(scope,source)).isInstanceOf(IOException.class).hasMessageNotContaining("sensitive");
    }
    @Test void publicBucketPolicyAclOrVersioningStopsBeforeAnyObjectWrite() throws Exception {
        var publicPolicy=new GetBucketPolicyStatusResult();publicPolicy.setPublic(true);
        when(oss.getBucketPolicyStatus(bucket)).thenReturn(publicPolicy);
        assertThatThrownBy(()->store.putBroadcast(scope,source)).isInstanceOf(IOException.class);
        when(oss.getBucketPolicyStatus(bucket)).thenReturn(new GetBucketPolicyStatusResult());
        for(String status:List.of(BucketVersioningConfiguration.ENABLED,BucketVersioningConfiguration.SUSPENDED)) {
            when(oss.getBucketVersioning(bucket)).thenReturn(new BucketVersioningConfiguration(status));
            assertThatThrownBy(()->store.putBroadcast(scope,source)).isInstanceOf(IOException.class);
        }
        when(oss.getBucketVersioning(bucket)).thenReturn(neverVersioned());
        var acl=new AccessControlList();acl.setCannedACL(CannedAccessControlList.PublicRead);when(oss.getBucketAcl(bucket)).thenReturn(acl);
        assertThatThrownBy(()->store.putBroadcast(scope,source)).isInstanceOf(IOException.class);verify(oss,never()).putObject(any(PutObjectRequest.class));
    }
    @Test void publicObjectTruncatedBodyFalseMetadataAndInvalidLocalWaveNeverCountAsSaved() {
        var acl=new ObjectAcl();acl.setPermission(ObjectPermission.PublicRead);when(oss.getObjectAcl(bucket,key())).thenReturn(acl);
        assertThatThrownBy(()->store.putBroadcast(scope,source)).isInstanceOf(IOException.class);
        acl.setPermission(ObjectPermission.Private);when(oss.getObject(bucket,key())).thenAnswer(i->{var obj=remote(wave);obj.getObjectMetadata().setContentLength(wave.length+2);return obj;});
        assertThatThrownBy(()->store.putBroadcast(scope,source)).isInstanceOf(IOException.class);
        when(oss.getObject(bucket,key())).thenAnswer(i->{var obj=remote(new byte[10]);obj.getObjectMetadata().setContentLength(wave.length);return obj;});
        assertThatThrownBy(()->store.putBroadcast(scope,source)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->store.putBroadcast(scope,folder.resolve("absent.wav"))).isInstanceOf(IOException.class);
    }
    @Test void scopeCannotAcceptArbitraryPathsCrossDeploymentOrInvalidIds() {
        assertThatThrownBy(()->new PreparedPrivateOssStore.Broadcast(0,scope.questionId(),scope.assetId())).isInstanceOf(IllegalArgumentException.class);
        for(String id:List.of("../file","BE1DC9A4-2B1E-4AD3-BB8B-A5FA041C2D9D","not-a-uuid"))
            assertThatThrownBy(()->new PreparedPrivateOssStore.Broadcast(4,id,scope.assetId())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void shortPreviewSignatureIsBoundToVerifiedObjectAndRedactedFromDiagnostics() throws Exception {
        var saved=store.putBroadcast(scope,source);
        when(oss.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenAnswer(i->{var request=i.getArgument(0,GeneratePresignedUrlRequest.class);
            assertThat(request.getKey()).isEqualTo(key());assertThat(request.getBucketName()).isEqualTo(bucket);
            assertThat(request.getExpiration().toInstant()).isEqualTo(clock.instant().plusSeconds(300));
            assertThat(request.getResponseHeaders().getCacheControl()).contains("no-store");
            return new URL("https://"+bucket+".oss-cn-beijing.aliyuncs.com/"+key()+"?Signature=sensitive-signature");});
        var preview=store.preview(saved);assertThat(preview.url()).contains("Signature=sensitive-signature");
        assertThat(preview.toString()).doesNotContain("sensitive-signature","Signature=");
        when(oss.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenReturn(new URL("http://"+bucket+".oss-cn-beijing.aliyuncs.com/"+key()+"?Signature=sensitive-signature"));
        assertThatThrownBy(()->store.preview(saved)).isInstanceOf(IOException.class).hasMessageNotContaining("sensitive-signature");
    }
    @Test void sdkExceptionsAreSanitizedAndClientIsClosedOnFailure() {
        when(oss.getBucketAcl(bucket)).thenThrow(new ClientException("secret-fixture private-key sensitive-signature"));
        assertThatThrownBy(()->store.putBroadcast(scope,source)).isInstanceOf(IOException.class).hasMessageNotContaining("secret-fixture").hasCause(null);
        verify(oss).shutdown();
    }
    @Test void malformedPrivateConfigurationAndDeploymentKeysAlwaysFailClosed() {
        for(var entry:List.of(Map.entry("endpoint","https://oss-cn-shanghai.aliyuncs.com"),Map.entry("bucket",bucket+"-"),
                Map.entry("access-key-secret","x".repeat(513)),Map.entry("access-key-secret","contains space"),
                Map.entry("access-key-secret","change-me"),Map.entry("bucket","x".repeat(1001))))
            assertThat(new PreparedStoragePolicy(config().withProperty("prepared.storage."+entry.getKey(),entry.getValue())).availability().enabled()).isFalse();
        assertThat(new PreparedStoragePolicy(config().withProperty("prepared.deployment-id","prepared-bad-")).availability().enabled()).isFalse();
        for(String namespace:Arrays.asList(null,"../old","prepared-bad-"))assertThatThrownBy(()->scope.key(namespace)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void missingPrivacyEvidenceOrWriteDenialNeverReportsSaved() {
        var validAcl=oss.getBucketAcl(bucket);var validVersion=oss.getBucketVersioning(bucket);var validStatus=oss.getBucketPolicyStatus(bucket);
        when(oss.getBucketAcl(bucket)).thenReturn(null);assertThatThrownBy(()->store.putBroadcast(scope,source)).isInstanceOf(IOException.class);
        when(oss.getBucketAcl(bucket)).thenReturn(validAcl);when(oss.getBucketVersioning(bucket)).thenReturn(null);assertThatThrownBy(()->store.putBroadcast(scope,source)).isInstanceOf(IOException.class);
        when(oss.getBucketVersioning(bucket)).thenReturn(validVersion);when(oss.getBucketPolicyStatus(bucket)).thenReturn(null);assertThatThrownBy(()->store.putBroadcast(scope,source)).isInstanceOf(IOException.class);
        when(oss.getBucketPolicyStatus(bucket)).thenReturn(validStatus);when(oss.getObjectAcl(bucket,key())).thenReturn(null);assertThatThrownBy(()->store.putBroadcast(scope,source)).isInstanceOf(IOException.class);
        when(oss.putObject(any(PutObjectRequest.class))).thenThrow(new OSSException("secret","AccessDenied","request","host",null,null,null));
        assertThatThrownBy(()->store.putBroadcast(scope,source)).isInstanceOf(IOException.class).hasMessageNotContaining("secret");
    }
    @Test void forgedProofsAndWrongObjectScopeAreRefusedBeforeSigning() throws Exception {
        var good=store.putBroadcast(scope,source);
        for(var proof:Arrays.asList(null,new system.assessment.defense.prepared.media.PreparedWaveAudio.Verified(45,good.proof().sha256(),1,24000,1,44,2),
                new system.assessment.defense.prepared.media.PreparedWaveAudio.Verified(67108865,good.proof().sha256(),1,24000,1,44,2),
                new system.assessment.defense.prepared.media.PreparedWaveAudio.Verified(good.proof().byteSize(),null,1000,24000,1,44,48000),
                new system.assessment.defense.prepared.media.PreparedWaveAudio.Verified(good.proof().byteSize(),"bad",1000,24000,1,44,48000)))
            assertThatThrownBy(()->store.preview(new PreparedPrivateOssStore.Stored(scope,key(),proof))).isInstanceOf(IOException.class);
        assertThatThrownBy(()->store.preview(new PreparedPrivateOssStore.Stored(scope,"prepared/other/file.wav",good.proof()))).isInstanceOf(IOException.class);
        verify(oss,never()).generatePresignedUrl(any(GeneratePresignedUrlRequest.class));
    }
    @Test void missingRemoteMetadataContentOrExtraBytesCannotCountAsVerified() {
        when(oss.getObject(bucket,key())).thenReturn(null);assertThatThrownBy(()->store.putBroadcast(scope,source)).isInstanceOf(IOException.class);
        when(oss.getObject(bucket,key())).thenAnswer(i->{var obj=remote(wave);obj.setObjectMetadata(null);return obj;});assertThatThrownBy(()->store.putBroadcast(scope,source)).isInstanceOf(IOException.class);
        when(oss.getObject(bucket,key())).thenAnswer(i->{var obj=remote(wave);obj.setObjectContent(null);return obj;});assertThatThrownBy(()->store.putBroadcast(scope,source)).isInstanceOf(IOException.class);
        when(oss.getObject(bucket,key())).thenAnswer(i->{var obj=remote(Arrays.copyOf(wave,wave.length+2));obj.getObjectMetadata().setContentLength(wave.length);return obj;});
        assertThatThrownBy(()->store.putBroadcast(scope,source)).isInstanceOf(IOException.class);
    }
    @Test void signatureCannotEscapeHostPathProtocolOrResponseLimits() throws Exception {
        var good=store.putBroadcast(scope,source);String base="https://"+bucket+".oss-cn-beijing.aliyuncs.com/"+key();
        for(String raw:List.of(base,base.replace(bucket+".oss-cn-beijing.aliyuncs.com","evil.invalid")+"?Signature=secret",
                base.replace("https://","https://user@")+"?Signature=secret",base.replace(".com/",".com:8443/")+"?Signature=secret",
                base+"?Signature=secret#fragment",base.replace(key(),"another.wav")+"?Signature=secret",base+"?Signature="+"x".repeat(4096))) {
            when(oss.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenReturn(new URL(raw));
            assertThatThrownBy(()->store.preview(good)).isInstanceOf(IOException.class).hasMessageNotContaining("Signature");
        }
    }
    @Test void assemblyDownloadsAnExactPrivateVerifiedObjectWithoutOverwritingLocalFiles() throws Exception {
        var saved=store.putBroadcast(scope,source);Path target=folder.resolve("download.wav");
        assertThat(store.download(saved,target)).isEqualTo(saved.proof());assertThat(Files.readAllBytes(target)).isEqualTo(wave);
        assertThatThrownBy(()->store.download(saved,target)).isInstanceOf(IOException.class);assertThat(Files.readAllBytes(target)).isEqualTo(wave);
        assertThatThrownBy(()->store.download(new PreparedPrivateOssStore.Stored(scope,"another.wav",saved.proof()),folder.resolve("not-created.wav"))).isInstanceOf(IOException.class);
        assertThat(folder.resolve("not-created.wav")).doesNotExist();
    }
    @Test void corruptDownloadDeletesOnlyItsNewArtifactAndRetainsUnrelatedFiles() throws Exception {
        var saved=store.putBroadcast(scope,source);byte[] corrupt=wave.clone();corrupt[100]^=1;when(oss.getObject(bucket,key())).thenAnswer(i->remote(corrupt));
        Path target=folder.resolve("invalid.wav");assertThatThrownBy(()->store.download(saved,target)).isInstanceOf(IOException.class);
        assertThat(target).doesNotExist();assertThat(Files.readAllBytes(source)).isEqualTo(wave);
        assertThatThrownBy(()->store.download(saved,folder.resolve("../escape.wav"))).isInstanceOf(IOException.class);
    }
}
