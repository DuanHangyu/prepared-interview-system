package system.assessment.defense.prepared;

import com.aliyun.oss.*;
import com.aliyun.oss.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real digest/streaming implementation against an explicit SDK double; NOT actual cloud acceptance. */
class PreparedAnswerBundleStorageTest {
    @TempDir Path directory;
    PreparedPrivateStorageTest f;
    byte[] bytes={1,2,3,4,5};
    Path source;
    PreparedPrivateOssStore.AnswerBundle bundle;
    String sha(byte[] value)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));}
    String key(){return bundle.key("prepared-fixture");}
    @BeforeEach void setup()throws Exception {
        f=new PreparedPrivateStorageTest();f.folder=directory;f.setup();source=directory.resolve("bundle.bin");Files.write(source,bytes);
        var attempt=UUID.randomUUID().toString();var answer=UUID.randomUUID().toString();var segment=UUID.randomUUID().toString();
        bundle=new PreparedPrivateOssStore.AnswerBundle("a".repeat(64),List.of(
            new PreparedPrivateOssStore.AnswerChunk(4,17,attempt,answer,segment,0,2,sha(new byte[]{1,2}),"audio/webm"),
            new PreparedPrivateOssStore.AnswerChunk(4,17,attempt,answer,segment,2,3,sha(new byte[]{3,4,5}),"audio/webm")),5,sha(bytes));
        var acl=new ObjectAcl();acl.setPermission(ObjectPermission.Private);when(f.oss.getObjectAcl(f.bucket,key())).thenReturn(acl);
        when(f.oss.getObject(f.bucket,key())).thenAnswer(i->f.remote(bytes));
    }
    @Test void multipleOriginalChunksShareOnePrivateObjectAndOneActualReadback()throws Exception {
        when(f.oss.putObject(any(PutObjectRequest.class))).thenAnswer(i->{var request=i.getArgument(0,PutObjectRequest.class);
            assertThat(request.getKey()).isEqualTo(key());assertThat(request.getInputStream().readAllBytes()).isEqualTo(bytes);
            var metadata=request.getMetadata();assertThat(metadata.getContentLength()).isEqualTo(5);
            assertThat(metadata.getContentType()).isEqualTo("application/octet-stream");assertThat(metadata.getContentMD5()).isNotBlank();
            assertThat(metadata.getServerSideEncryption()).isEqualTo("AES256");
            assertThat(metadata.getRawMetadata()).containsEntry("x-oss-forbid-overwrite","true").containsEntry("x-oss-object-acl","private");return new PutObjectResult();});
        assertThat(f.store.putAnswerBundle(bundle,source)).isEqualTo(new PreparedPrivateOssStore.StoredBundle(bundle,key()));
        verify(f.oss,times(1)).putObject(any(PutObjectRequest.class));verify(f.oss,times(1)).getObject(f.bucket,key());verify(f.oss).shutdown();
    }
    @Test void authorizedWorkerDownloadsRealBundleBytesOnceToNewPrivateFileNotLegacySingleFallback()throws Exception {
        var target=directory.toRealPath().resolve("download.bin");
        f.store.downloadAnswerBundle(new PreparedPrivateOssStore.StoredBundle(bundle,key()),target);
        assertThat(Files.readAllBytes(target)).isEqualTo(bytes);verify(f.oss,times(1)).getObject(f.bucket,key());
        verify(f.oss,never()).getObject(f.bucket,bundle.chunks().getFirst().key("prepared-fixture"));
        assertThat(Files.getPosixFilePermissions(target)).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        assertThatThrownBy(()->f.store.downloadAnswerBundle(new PreparedPrivateOssStore.StoredBundle(bundle,key()),target)).isInstanceOf(IOException.class);
        assertThat(Files.readAllBytes(target)).isEqualTo(bytes);
    }
    @Test void downloadRequiresActualWholeAndPerChunkHashesAndNeverDeletesOrOverwritesOriginals()throws Exception {
        var target=directory.toRealPath().resolve("invalid-download.bin");
        when(f.oss.getObject(f.bucket,key())).thenAnswer(i->f.remote(new byte[5]));
        assertThatThrownBy(()->f.store.downloadAnswerBundle(new PreparedPrivateOssStore.StoredBundle(bundle,key()),target)).isInstanceOf(IOException.class);
        assertThat(Files.exists(target)).isFalse();verify(f.oss,never()).deleteObject(anyString(),anyString());
        assertThatThrownBy(()->f.store.downloadAnswerBundle(new PreparedPrivateOssStore.StoredBundle(bundle,"prepared/foreign"),target)).isInstanceOf(IOException.class);
    }
    @Test void singleOriginalDownloadAndCanonicalWholeAnswerUseTheirOwnPrivateKeysAndProofs()throws Exception {
        var chunk=bundle.chunks().getFirst();String single=chunk.key("prepared-fixture");var acl=new ObjectAcl();acl.setPermission(ObjectPermission.Private);
        when(f.oss.getObjectAcl(f.bucket,single)).thenReturn(acl);when(f.oss.getObject(f.bucket,single)).thenAnswer(i->f.remote(new byte[]{1,2}));
        Path downloaded=directory.toRealPath().resolve("single.bin");f.store.downloadAnswerChunk(new PreparedPrivateOssStore.StoredChunk(chunk,single),downloaded);assertThat(Files.readAllBytes(downloaded)).containsExactly(1,2);
        var scope=new PreparedPrivateOssStore.AnswerFile(chunk.assessmentId(),chunk.studentId(),chunk.attemptId(),chunk.answerId(),"c".repeat(64));String whole=scope.key("prepared-fixture");
        when(f.oss.getObjectAcl(f.bucket,whole)).thenReturn(acl);when(f.oss.getObject(f.bucket,whole)).thenAnswer(i->f.remote(f.wave));
        var saved=f.store.putAnswer(scope,f.source);assertThat(saved.objectKey()).isEqualTo(whole);assertThat(saved.proof().durationMs()).isEqualTo(1000);
        when(f.oss.doesObjectExist(f.bucket,whole)).thenReturn(true);assertThat(f.store.putAnswer(scope,f.source)).isEqualTo(saved);
        when(f.oss.getObject(f.bucket,whole)).thenAnswer(i->f.remote(new byte[f.wave.length]));assertThatThrownBy(()->f.store.putAnswer(scope,f.source)).isInstanceOf(IOException.class);
        verify(f.oss,never()).deleteObject(anyString(),anyString());
    }
    @Test void downloadRejectsPublicMissingLongShortCorruptAndSymlinkFilesWithoutDeletingUnrelatedData()throws Exception {
        var real=directory.toRealPath();int i=0;
        for(byte[] bad:List.of(new byte[4],new byte[5],new byte[6])) {
            when(f.oss.getObject(f.bucket,key())).thenAnswer(x->{var obj=f.remote(bad);obj.getObjectMetadata().setContentLength(5);return obj;});
            Path target=real.resolve("bad-"+i+++".bin");assertThatThrownBy(()->f.store.downloadAnswerBundle(new PreparedPrivateOssStore.StoredBundle(bundle,key()),target)).isInstanceOf(IOException.class);assertThat(Files.exists(target)).isFalse();
        }
        var acl=new ObjectAcl();acl.setPermission(ObjectPermission.PublicRead);when(f.oss.getObjectAcl(f.bucket,key())).thenReturn(acl);
        assertThatThrownBy(()->f.store.downloadAnswerBundle(new PreparedPrivateOssStore.StoredBundle(bundle,key()),real.resolve("public.bin"))).isInstanceOf(IOException.class);
        Path alias=real.resolve("alias");Files.createSymbolicLink(alias,real);
        assertThatThrownBy(()->f.store.downloadAnswerBundle(new PreparedPrivateOssStore.StoredBundle(bundle,key()),alias.resolve("escape.bin"))).isInstanceOf(IOException.class);
        assertThatThrownBy(()->new PreparedPrivateOssStore.AnswerFile(0,1,bundle.chunks().getFirst().attemptId(),bundle.chunks().getFirst().answerId(),"a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void wholeDigestDoesNotReplaceEachOriginalChunkDigest()throws Exception {
        var first=bundle.chunks().getFirst();var bad=new PreparedPrivateOssStore.AnswerChunk(4,17,first.attemptId(),first.answerId(),first.segmentId(),0,2,"b".repeat(64),"audio/webm");
        var falseClaim=new PreparedPrivateOssStore.AnswerBundle(bundle.id(),List.of(bad,bundle.chunks().getLast()),5,sha(bytes));
        assertThatThrownBy(()->f.store.putAnswerBundle(falseClaim,source)).isInstanceOf(IOException.class);verifyNoInteractions(f.oss);
    }
    @Test void falseRemoteMetadataSameLengthCorruptionTruncationAndExtraTailCannotConfirm() {
        for(byte[] bad:List.of(new byte[5],new byte[4],new byte[6])) {
            when(f.oss.getObject(f.bucket,key())).thenAnswer(i->{var object=f.remote(bad);object.getObjectMetadata().setContentLength(5);return object;});
            assertThatThrownBy(()->f.store.putAnswerBundle(bundle,source)).isInstanceOf(IOException.class);
        }
        verify(f.oss,never()).deleteObject(anyString(),anyString());
    }
    @Test void ambiguousExistingObjectOnlyRecoversActualOriginalBytes()throws Exception {
        doThrow(new OSSException("private vendor URL","FileAlreadyExists","request","host",null,null,null)).when(f.oss).putObject(any(PutObjectRequest.class));
        assertThat(f.store.putAnswerBundle(bundle,source).scope()).isEqualTo(bundle);
        when(f.oss.doesObjectExist(f.bucket,key())).thenReturn(true);assertThat(f.store.putAnswerBundle(bundle,source).scope()).isEqualTo(bundle);
        verify(f.oss,times(1)).putObject(any(PutObjectRequest.class));
        when(f.oss.getObject(f.bucket,key())).thenAnswer(i->f.remote(new byte[5]));
        assertThatThrownBy(()->f.store.putAnswerBundle(bundle,source)).isInstanceOf(IOException.class).hasMessageNotContaining("vendor");
    }
    @Test void publicObjectOrUnusableLocalSourceNeverBecomesAReceipt()throws Exception {
        var acl=new ObjectAcl();acl.setPermission(ObjectPermission.PublicRead);when(f.oss.getObjectAcl(f.bucket,key())).thenReturn(acl);
        assertThatThrownBy(()->f.store.putAnswerBundle(bundle,source)).isInstanceOf(IOException.class);
        reset(f.oss);Files.createSymbolicLink(directory.resolve("link"),source);
        assertThatThrownBy(()->f.store.putAnswerBundle(bundle,directory.resolve("link"))).isInstanceOf(IOException.class);verifyNoInteractions(f.oss);
    }
    @Test void bundleScopeCannotMixAnswersFormatsOrOrderAndRemainsBounded() {
        var first=bundle.chunks().getFirst();
        assertThat(key()).contains("/students/17/assessments/4/attempts/").endsWith("/bundles/"+bundle.id()+".bin");
        for(var chunks:List.of(List.of(first,first),List.of(bundle.chunks().getLast(),first),List.<PreparedPrivateOssStore.AnswerChunk>of(),Collections.nCopies(33,first)))
            assertThatThrownBy(()->new PreparedPrivateOssStore.AnswerBundle(bundle.id(),chunks,5,bundle.sha256())).isInstanceOf(IllegalArgumentException.class);
        var foreign=new PreparedPrivateOssStore.AnswerChunk(4,18,first.attemptId(),first.answerId(),first.segmentId(),2,3,bundle.chunks().getLast().sha256(),"audio/webm");
        assertThatThrownBy(()->new PreparedPrivateOssStore.AnswerBundle(bundle.id(),List.of(first,foreign),5,bundle.sha256())).isInstanceOf(IllegalArgumentException.class);
        for(int size:new int[]{0,6,8388609})assertThatThrownBy(()->new PreparedPrivateOssStore.AnswerBundle(bundle.id(),bundle.chunks(),size,bundle.sha256())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->bundle.key("../old")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void everyIdentityDimensionAndFormatMustMatchTheFirstOriginalChunk() {
        var first=bundle.chunks().getFirst();var last=bundle.chunks().getLast();
        var foreign=List.of(
            new PreparedPrivateOssStore.AnswerChunk(5,17,first.attemptId(),first.answerId(),first.segmentId(),2,3,last.sha256(),"audio/webm"),
            new PreparedPrivateOssStore.AnswerChunk(4,17,UUID.randomUUID().toString(),first.answerId(),first.segmentId(),2,3,last.sha256(),"audio/webm"),
            new PreparedPrivateOssStore.AnswerChunk(4,17,first.attemptId(),UUID.randomUUID().toString(),first.segmentId(),2,3,last.sha256(),"audio/webm"),
            new PreparedPrivateOssStore.AnswerChunk(4,17,first.attemptId(),first.answerId(),UUID.randomUUID().toString(),2,3,last.sha256(),"audio/webm"),
            new PreparedPrivateOssStore.AnswerChunk(4,17,first.attemptId(),first.answerId(),first.segmentId(),2,3,last.sha256(),"audio/mp4"));
        for(var chunk:foreign)assertThatThrownBy(()->new PreparedPrivateOssStore.AnswerBundle(bundle.id(),List.of(first,chunk),5,bundle.sha256())).isInstanceOf(IllegalArgumentException.class);
        for(String id:Arrays.asList(null,"../old"))assertThatThrownBy(()->new PreparedPrivateOssStore.AnswerBundle(id,bundle.chunks(),5,bundle.sha256())).isInstanceOf(IllegalArgumentException.class);
        for(String hash:Arrays.asList(null,"A".repeat(64)))assertThatThrownBy(()->new PreparedPrivateOssStore.AnswerBundle(bundle.id(),bundle.chunks(),5,hash)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new PreparedPrivateOssStore.AnswerBundle(bundle.id(),null,5,bundle.sha256())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void absentOrFalseRemoteProofCannotConfirmEvenWhenMetadataClaimsAnObjectExists() {
        var absentMetadata=new OSSObject();absentMetadata.setObjectContent(new ByteArrayInputStream(bytes));absentMetadata.setObjectMetadata(null);
        var absentBytes=f.remote(bytes);absentBytes.setObjectContent(null);
        var wrongSize=f.remote(bytes);wrongSize.getObjectMetadata().setContentLength(4);
        for(var object:Arrays.asList(null,absentMetadata,absentBytes,wrongSize)) {
            when(f.oss.getObject(f.bucket,key())).thenReturn(object);assertThatThrownBy(()->f.store.putAnswerBundle(bundle,source)).isInstanceOf(IOException.class);
        }
        when(f.oss.getObjectAcl(f.bucket,key())).thenReturn(null);assertThatThrownBy(()->f.store.putAnswerBundle(bundle,source)).isInstanceOf(IOException.class);
        verify(f.oss,never()).deleteObject(anyString(),anyString());
    }
    @Test void unrelatedProviderWriteErrorsAreSanitizedAndNeverPretendAmbiguousSuccess() {
        doThrow(new OSSException("private vendor URL","AccessDenied","request","host",null,null,null)).when(f.oss).putObject(any(PutObjectRequest.class));
        assertThatThrownBy(()->f.store.putAnswerBundle(bundle,source)).isInstanceOf(IOException.class).hasMessageNotContaining("vendor");
        verify(f.oss,never()).getObject(f.bucket,key());verify(f.oss).shutdown();verify(f.oss,never()).deleteObject(anyString(),anyString());
    }
    @Test void validIndividualDigestsNeverAuthorizeAFalseWholeDigestOrAnIncompleteStream()throws Exception {
        var wrongHash=new PreparedPrivateOssStore.AnswerBundle(bundle.id(),bundle.chunks(),5,"b".repeat(64));
        assertThatThrownBy(()->f.store.putAnswerBundle(wrongHash,source)).isInstanceOf(IOException.class);verifyNoInteractions(f.oss);
        assertThatThrownBy(()->PreparedPrivateOssStore.verifyAnswerBundleBytes(new ByteArrayInputStream(new byte[]{1}),bundle)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->PreparedPrivateOssStore.verifyAnswerBundleBytes(new ByteArrayInputStream(new byte[]{1,2,3,4,5,6}),bundle)).isInstanceOf(IOException.class);
        for(Path invalid:Arrays.asList(null,Path.of("relative.bin"),directory.resolve("missing"),directory.resolve("nested/../bundle.bin")))
            assertThatThrownBy(()->f.store.putAnswerBundle(bundle,invalid)).isInstanceOf(IOException.class);
    }
}
