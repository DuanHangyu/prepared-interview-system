package system.assessment.defense.prepared;

import com.aliyun.oss.*;
import com.aliyun.oss.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual streaming/digest logic against an explicitly synthetic OSS SDK, NOT cloud acceptance. */
class PreparedAnswerChunkStorageTest {
    @TempDir Path directory;
    PreparedPrivateStorageTest fixture;
    byte[] bytes={1,2,3,4,5};
    Path source;
    PreparedPrivateOssStore.AnswerChunk scope;
    String hash(byte[] value)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));}
    String key(){return scope.key("prepared-fixture");}
    @BeforeEach void setup()throws Exception {
        fixture=new PreparedPrivateStorageTest();fixture.folder=directory;fixture.setup();source=directory.resolve("chunk.bin");Files.write(source,bytes);
        scope=new PreparedPrivateOssStore.AnswerChunk(4,17,UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),0,bytes.length,hash(bytes),"audio/webm;codecs=opus");
        var acl=new ObjectAcl();acl.setPermission(ObjectPermission.Private);when(fixture.oss.getObjectAcl(fixture.bucket,key())).thenReturn(acl);
        when(fixture.oss.getObject(fixture.bucket,key())).thenAnswer(i->fixture.remote(bytes));
    }
    @Test void streamedPrivateNonOverwritingChunkIsConfirmedOnlyAfterActualRemoteShaAndLength()throws Exception {
        when(fixture.oss.putObject(any(PutObjectRequest.class))).thenAnswer(i->{var request=i.getArgument(0,PutObjectRequest.class);
            assertThat(request.getKey()).isEqualTo(key());assertThat(request.getInputStream().readAllBytes()).isEqualTo(bytes);
            assertThat(request.getMetadata().getContentLength()).isEqualTo(bytes.length);
            assertThat(request.getMetadata().getContentType()).isEqualTo("application/octet-stream");
            assertThat(request.getMetadata().getContentMD5()).isNotBlank();
            assertThat(request.getMetadata().getServerSideEncryption()).isEqualTo("AES256");
            assertThat(request.getMetadata().getRawMetadata()).containsEntry("x-oss-forbid-overwrite","true");return new PutObjectResult();});
        var saved=fixture.store.putAnswerChunk(scope,source);assertThat(saved.scope()).isEqualTo(scope);assertThat(saved.objectKey()).isEqualTo(key());
        verify(fixture.oss).getObject(fixture.bucket,key());verify(fixture.oss).shutdown();
    }
    @Test void existingIdenticalBytesRecoverWithoutWriteButDifferentSameSizeBytesNeverConfirm()throws Exception {
        when(fixture.oss.doesObjectExist(fixture.bucket,key())).thenReturn(true);
        assertThat(fixture.store.putAnswerChunk(scope,source).objectKey()).isEqualTo(key());verify(fixture.oss,never()).putObject(any(PutObjectRequest.class));
        when(fixture.oss.getObject(fixture.bucket,key())).thenAnswer(i->fixture.remote(new byte[bytes.length]));
        assertThatThrownBy(()->fixture.store.putAnswerChunk(scope,source)).isInstanceOf(IOException.class);
        verify(fixture.oss,never()).deleteObject(anyString(),anyString());
    }
    @Test void falseRemoteMetadataTruncationOversizeAndPublicObjectsAreNotProof() {
        for(byte[] bad:List.of(new byte[4],new byte[6])){
            when(fixture.oss.getObject(fixture.bucket,key())).thenAnswer(i->{var object=fixture.remote(bad);object.getObjectMetadata().setContentLength(bytes.length);return object;});
            assertThatThrownBy(()->fixture.store.putAnswerChunk(scope,source)).isInstanceOf(IOException.class);
        }
        var acl=new ObjectAcl();acl.setPermission(ObjectPermission.PublicRead);when(fixture.oss.getObjectAcl(fixture.bucket,key())).thenReturn(acl);
        assertThatThrownBy(()->fixture.store.putAnswerChunk(scope,source)).isInstanceOf(IOException.class);
    }
    @Test void invalidLocalBytesOrSymlinkFailBeforeAnySdkCall()throws Exception {
        Files.write(source,new byte[bytes.length]);assertThatThrownBy(()->fixture.store.putAnswerChunk(scope,source)).isInstanceOf(IOException.class);
        Files.createSymbolicLink(directory.resolve("link"),source);assertThatThrownBy(()->fixture.store.putAnswerChunk(scope,directory.resolve("link"))).isInstanceOf(IOException.class);
        verifyNoInteractions(fixture.oss);
    }
    @Test void scopeUsesOnlyCanonicalOwnedIdentifiersAndBoundedAudioChunkClaims()throws Exception {
        assertThat(key()).startsWith("prepared/prepared-fixture/students/17/assessments/4/attempts/").endsWith("/chunks/0.bin");
        assertThatThrownBy(()->new PreparedPrivateOssStore.AnswerChunk(0,17,scope.attemptId(),scope.answerId(),scope.segmentId(),0,5,scope.sha256(),scope.mimeType())).isInstanceOf(IllegalArgumentException.class);
        for(String mime:List.of("video/webm","audio/wav","audio/webm;codecs=opus\npublic"))assertThatThrownBy(()->new PreparedPrivateOssStore.AnswerChunk(4,17,scope.attemptId(),scope.answerId(),scope.segmentId(),0,5,scope.sha256(),mime)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->scope.key("../old")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedPrivateOssStore.AnswerChunk(4,17,scope.attemptId(),scope.answerId(),"../file",0,5,scope.sha256(),scope.mimeType())).isInstanceOf(IllegalArgumentException.class);
        for(int size:new int[]{0,2097153})assertThatThrownBy(()->new PreparedPrivateOssStore.AnswerChunk(4,17,scope.attemptId(),scope.answerId(),scope.segmentId(),0,size,scope.sha256(),scope.mimeType())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void ambiguousNonOverwriteResponseOnlyRecoversVerifiedOriginalObject()throws Exception {
        doThrow(new OSSException("vendor signed URL","FileAlreadyExists","request","host",null,null,null)).when(fixture.oss).putObject(any(PutObjectRequest.class));
        assertThat(fixture.store.putAnswerChunk(scope,source).scope()).isEqualTo(scope);
        when(fixture.oss.getObject(fixture.bucket,key())).thenAnswer(i->fixture.remote(new byte[5]));
        assertThatThrownBy(()->fixture.store.putAnswerChunk(scope,source)).isInstanceOf(IOException.class).hasMessageNotContaining("vendor");
        verify(fixture.oss,never()).deleteObject(anyString(),anyString());
    }
}
