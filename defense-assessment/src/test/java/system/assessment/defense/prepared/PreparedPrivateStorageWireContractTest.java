package system.assessment.defense.prepared;

import com.aliyun.oss.*;
import com.aliyun.oss.internal.ResponseParsers;
import com.aliyun.oss.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.mock.env.MockEnvironment;
import system.assessment.defense.prepared.storage.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real installed SDK parses official wire response; remote transport is an
 * explicit double. This is NOT actual cloud upload/receipt acceptance.
 * Source: https://help.aliyun.com/zh/oss/developer-reference/getbucketversioning */
class PreparedPrivateStorageWireContractTest {
    @TempDir Path folder;
    private static final String BUCKET="prepared-interview-wire-fixture";
    private static final String DEPLOYMENT="prepared-wire-fixture";
    private static final byte[] BYTES={1,2,3,4};
    private OSS oss;
    private PreparedPrivateOssStore store;
    private PreparedPrivateOssStore.AnswerChunk scope;
    private Path source;
    @BeforeEach void setup()throws Exception{
        folder=folder.toRealPath();source=folder.resolve("owned-original.bin");Files.write(source,BYTES);
        var env=new MockEnvironment().withProperty("prepared.storage.enabled","true")
            .withProperty("prepared.storage.endpoint","https://oss-cn-beijing.aliyuncs.com")
            .withProperty("prepared.storage.bucket",BUCKET).withProperty("prepared.deployment-id",DEPLOYMENT)
            // Dummy values reach only the injected local factory, never an SDK client builder.
            .withProperty("prepared.storage.access-key-id","wire-fixture-no-real-identity")
            .withProperty("prepared.storage.access-key-secret","wire-fixture-no-real-secret");
        scope=new PreparedPrivateOssStore.AnswerChunk(4,2,"be1dc9a4-2b1e-4ad3-bb8b-a5fa041c2d9d",
            "0aa711cd-223a-4f06-a7f6-ccad2df141f4","61d7a83d-09a8-4d14-bcdc-036e093c9ef0",
            0,BYTES.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BYTES)),"audio/webm");
        oss=mock(OSS.class);var bucketAcl=new AccessControlList();bucketAcl.setCannedACL(CannedAccessControlList.Private);
        when(oss.getBucketAcl(BUCKET)).thenReturn(bucketAcl);
        when(oss.getBucketVersioning(BUCKET)).thenReturn(parse(neverVersionedResponses().getFirst()));
        when(oss.getBucketPolicyStatus(BUCKET)).thenReturn(new GetBucketPolicyStatusResult());
        when(oss.putObject(any(PutObjectRequest.class))).thenAnswer(invocation->{
            var request=invocation.getArgument(0,PutObjectRequest.class);
            assertThat(request.getInputStream().readAllBytes()).isEqualTo(BYTES);
            assertThat(request.getMetadata().getRawMetadata()).containsEntry("x-oss-forbid-overwrite","true");
            return new PutObjectResult();
        });
        var objectAcl=new ObjectAcl();objectAcl.setPermission(ObjectPermission.Private);
        when(oss.getObjectAcl(BUCKET,scope.key(DEPLOYMENT))).thenReturn(objectAcl);
        when(oss.getObject(BUCKET,scope.key(DEPLOYMENT))).thenAnswer(invocation->{
            var object=new OSSObject();var metadata=new ObjectMetadata();metadata.setContentLength(BYTES.length);
            object.setObjectMetadata(metadata);object.setObjectContent(new ByteArrayInputStream(BYTES));return object;
        });
        store=new PreparedPrivateOssStore(new PreparedStoragePolicy(env),(p,id,secret)->oss,Clock.systemUTC());
    }
    static List<String> neverVersionedResponses(){return List.of(
        "<VersioningConfiguration xmlns=\"http://doc.oss-cn-hangzhou.aliyuncs.com\"/>",
        "<VersioningConfiguration/>");}
    private static BucketVersioningConfiguration parse(String xml)throws Exception{
        return ResponseParsers.parseGetBucketVersioning(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
    @ParameterizedTest @MethodSource("neverVersionedResponses")
    void officialMissingStatusResponsePassesOnlyAfterActualOriginalBytesAreVerified(String xml)throws Exception{
        var versioning=parse(xml);assertThat(versioning).isNotNull();assertThat(versioning.getStatus()).isNull();
        when(oss.getBucketVersioning(BUCKET)).thenReturn(versioning);
        var receipt=new AtomicReference<PreparedPrivateOssStore.StoredChunk>();
        assertThatCode(()->receipt.set(store.putAnswerChunk(scope,source))).doesNotThrowAnyException();
        assertThat(receipt.get()).isEqualTo(new PreparedPrivateOssStore.StoredChunk(scope,scope.key(DEPLOYMENT)));
        verify(oss).putObject(any(PutObjectRequest.class));verify(oss).getObject(BUCKET,scope.key(DEPLOYMENT));verify(oss).shutdown();
    }
    @ParameterizedTest @ValueSource(strings={"Enabled","Suspended","Off","","Unknown"," Enabled "})
    void explicitVersionedOrUnrecognizedStatusIsRejectedBeforeAnyObjectIo(String status)throws Exception{
        var versioning=parse("<VersioningConfiguration><Status>"+status+"</Status></VersioningConfiguration>");
        assertThat(versioning.getStatus()).isEqualTo(status);when(oss.getBucketVersioning(BUCKET)).thenReturn(versioning);
        assertThatThrownBy(()->store.putAnswerChunk(scope,source)).isInstanceOf(IOException.class);
        verify(oss,never()).putObject(any(PutObjectRequest.class));verify(oss,never()).getObject(anyString(),anyString());verify(oss).shutdown();
    }
    @Test void missingWholeSdkResultIsNotTheOfficialMissingStatusResponse()throws Exception{
        when(oss.getBucketVersioning(BUCKET)).thenReturn(null);
        assertThatThrownBy(()->store.putAnswerChunk(scope,source)).isInstanceOf(IOException.class);
        verify(oss,never()).putObject(any(PutObjectRequest.class));verify(oss,never()).getObject(anyString(),anyString());verify(oss).shutdown();
    }
    @Test void versioningQueryErrorIsNotConvertedIntoAnUnversionedSuccess()throws Exception{
        when(oss.getBucketVersioning(BUCKET)).thenThrow(new ClientException("private fixture vendor body"));
        assertThatThrownBy(()->store.putAnswerChunk(scope,source)).isInstanceOf(IOException.class).hasCause(null).hasMessageNotContaining("vendor");
        verify(oss,never()).putObject(any(PutObjectRequest.class));verify(oss,never()).getObject(anyString(),anyString());verify(oss).shutdown();
    }
}
