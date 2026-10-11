package system.assessment.defense.prepared.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import system.assessment.defense.prepared.attempt.*;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.media.PreparedWaveAudio;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Internal ORIGINAL registered object inventory, not an authorization/deletion
 * permit, remote listing, remote-byte proof or proof that scratch/backups exited.
 * Trusted submission originals and all physical-layout rows are reloaded under
 * the same parent transaction. Unknown/extra rows are NEVER silently omitted.
 * Logical chunks inside a bundle are not separate physical single-chunk objects.
 * No controller/scheduler/storage call or lifecycle mutation exists here. */
public final class PreparedOriginalAudioInventory {
    public sealed interface Item permits Single,Bundle,Whole {
        String kind();String key();long byteSize();String sha256();
    }
    public record Single(StoredChunk original,String deployment) implements Item {
        public Single{
            if(original==null||original.scope()==null||!original.scope().key(deployment).equals(original.objectKey()))throw invalidItem();
        }
        public String kind(){return "CHUNK";}
        public String key(){return original.objectKey();}
        public long byteSize(){return original.scope().byteSize();}
        public String sha256(){return original.scope().sha256();}
        @Override public String toString(){return "OriginalSingle[private scope redacted]";}
    }
    public record Bundle(StoredBundle original,String deployment) implements Item {
        public Bundle{
            if(original==null||original.scope()==null||!original.scope().key(deployment).equals(original.objectKey()))throw invalidItem();
        }
        public String kind(){return "BUNDLE";}
        public String key(){return original.objectKey();}
        public long byteSize(){return original.scope().byteSize();}
        public String sha256(){return original.scope().sha256();}
        @Override public String toString(){return "OriginalBundle[private scope redacted]";}
    }
    public record Whole(StoredAnswer original,String deployment) implements Item {
        public Whole{
            if(original==null||original.scope()==null||!original.scope().key(deployment).equals(original.objectKey())||!validWave(original.proof()))throw invalidItem();
        }
        public String kind(){return "ANSWER";}
        public String key(){return original.objectKey();}
        public long byteSize(){return original.proof().byteSize();}
        public String sha256(){return original.proof().sha256();}
        @Override public String toString(){return "OriginalWhole[private scope redacted]";}
    }
    public record Snapshot(List<Item> objects,long byteSize,String sha256){
        public Snapshot{objects=List.copyOf(objects);}
        @Override public String toString(){return "OriginalAudioInventory[count="+objects.size()+", private scope redacted]";}
    }
    private final PreparedSubmissionService submissions;
    private final JdbcTemplate jdbc;
    private final PreparedAnswerEndRepository endings;
    private final PreparedAnswerUploadRepository uploads;
    private final PreparedAnswerBundleRepository bundles;
    public PreparedOriginalAudioInventory(PreparedSubmissionService submissions,JdbcTemplate jdbc){
        this.submissions=Objects.requireNonNull(submissions);this.jdbc=Objects.requireNonNull(jdbc);
        endings=new PreparedAnswerEndRepository(jdbc);uploads=new PreparedAnswerUploadRepository(jdbc);bundles=new PreparedAnswerBundleRepository(jdbc);
    }
    /** Must join the caller's ORIGINAL parent transaction; not a stale standalone
     * plan. The trusted service reacquires the same parent/original scope first. */
    public Snapshot read(int student,int assessment,String attempt){
        if(student<1||assessment<1)throw new IllegalArgumentException("原录音清单范围无效");PreparedJobInput.uuid(attempt);
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw invalid();
        var sources=submissions.retentionSources(student,assessment,attempt).sources();
        if(sources.isEmpty()||sources.size()>50)throw invalid();
        var answers=new LinkedHashSet<String>();var objects=new ArrayList<Item>();
        for(var source:sources){
            var work=source.binding().input();
            if(work.student()!=student||work.assessment()!=assessment||!attempt.equals(work.attempt())||!answers.add(work.answer()))throw invalid();
            PreparedJobInput.uuid(work.answer());var whole=source.media();
            var headers=jdbc.query("SELECT capture_client_id,answer_seconds FROM prepared_answer WHERE assessment_id=? AND student_id=? AND attempt_id=? AND id=? FOR UPDATE",
                (rs,n)->new AnswerHeader(rs.getString(1),rs.getInt(2)),assessment,student,attempt,work.answer());
            if(headers.size()!=1)throw invalid();var header=headers.getFirst();PreparedJobInput.uuid(header.client());
            var media=jdbc.queryForList("SELECT deployment_id FROM prepared_answer_media WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=? FOR UPDATE",String.class,assessment,student,attempt,work.answer());
            if(media.size()!=1)throw invalid();String deployment=media.getFirst();
            var expected=new AnswerFile(assessment,student,attempt,work.answer(),work.manifestSha256());
            if(whole==null||!expected.equals(whole.scope()))throw invalid();
            var head=endings.head(student,assessment,attempt,work.answer()).orElseThrow(PreparedOriginalAudioInventory::invalid);
            var manifest=PreparedAnswerEnd.decode(head.manifest());var proof=PreparedAnswerEnd.inspect(manifest,header.seconds());
            if(!proof.json().equals(PreparedJobInput.json(head.manifest(),2*1024*1024))||!proof.sha256().equals(work.manifestSha256())||!head.hash().equals(proof.sha256())
                ||head.bytes()!=proof.byteSize()||!head.reasons().isEmpty()||!proof.reviewReasons().isEmpty())throw invalid();
            var segmentRows=jdbc.query("SELECT id,ordinal,mime_type FROM prepared_answer_segment WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=? ORDER BY ordinal LIMIT 1001 FOR UPDATE",
                (rs,n)->new SegmentHeader(rs.getString(1),rs.getInt(2),rs.getString(3)),assessment,student,attempt,work.answer());
            var expectedSegments=manifest.segments().stream().map(v->new SegmentHeader(v.id(),v.ordinal(),v.mimeType())).toList();
            if(!segmentRows.equals(expectedSegments))throw invalid();
            var locations=uploads.locations(student,assessment,attempt,work.answer());
            int count=manifest.segments().stream().mapToInt(PreparedAnswerEnd.Segment::chunkCount).sum();
            if(count!=locations.size()||count<1||count>10000)throw invalid();
            var groups=new LinkedHashMap<String,PreparedAnswerBundleRepository.Row>();int cursor=0;
            for(var segment:manifest.segments())for(var declared:segment.chunks()){
                var location=locations.get(cursor++);var row=location.row();
                var chunk=new AnswerChunk(assessment,student,attempt,work.answer(),segment.id(),declared.index(),declared.byteSize(),declared.sha256(),segment.mimeType());
                if(!chunk.equals(row.scope())||row.ordinal()!=segment.ordinal()||!deployment.equals(row.deployment())
                    ||!chunk.key(deployment).equals(row.objectKey())||row.confirmed()==null||row.confirmed().isBefore(row.reserved()))throw invalid();
                if(location.bundleId()==null){
                    if(location.offset()!=null)throw invalid();objects.add(new Single(new StoredChunk(chunk,row.objectKey()),deployment));
                }else{
                    var group=groups.computeIfAbsent(location.bundleId(),id->bundles.find(student,assessment,attempt,work.answer(),id).orElseThrow(PreparedOriginalAudioInventory::invalid));
                    if(!deployment.equals(group.deployment())||!header.client().equals(group.command().captureClientId())||group.confirmed()==null
                        ||group.confirmed().isBefore(group.reserved())||!group.confirmed().equals(row.confirmed()))throw invalid();
                    int offset=0;boolean matched=false;
                    for(var selected:group.scope().chunks()){if(selected.equals(chunk)){matched=Objects.equals(location.offset(),offset);break;}offset+=selected.byteSize();}
                    if(!matched)throw invalid();
                }
            }
            var allGroups=jdbc.queryForList("SELECT id FROM prepared_answer_upload_bundle WHERE assessment_id=? AND student_id=? AND attempt_id=? AND answer_id=? ORDER BY id LIMIT 10001 FOR UPDATE",String.class,assessment,student,attempt,work.answer());
            if(allGroups.size()>10000||allGroups.size()!=groups.size()||!new HashSet<>(allGroups).equals(groups.keySet()))throw invalid();
            for(var group:groups.values()){
                var linked=uploads.bundleRows(student,assessment,attempt,work.answer(),group.scope().id());
                if(linked.size()!=group.scope().chunks().size())throw invalid();
                for(int n=0;n<linked.size();n++)if(!linked.get(n).scope().equals(group.scope().chunks().get(n))||!group.confirmed().equals(linked.get(n).confirmed()))throw invalid();
                objects.add(new Bundle(new StoredBundle(group.scope(),group.key()),deployment));
            }
            objects.add(new Whole(whole,deployment));
        }
        // Even an empty extra segment/header from the SAME original must not be
        // hidden by only walking the successful submission's known answers.
        for(String table:List.of("prepared_answer","prepared_answer_end","prepared_answer_media","prepared_answer_segment","prepared_answer_chunk","prepared_answer_upload_bundle")){
            String id=table.equals("prepared_answer")?"id":"answer_id";
            var registered=jdbc.queryForList("SELECT DISTINCT "+id+" FROM "+table+" WHERE assessment_id=? AND student_id=? AND attempt_id=? LIMIT 51 FOR UPDATE",String.class,assessment,student,attempt);
            var seen=new HashSet<>(registered);
            // An answer may contain only standalone uploads, no bundle header.
            if(!answers.containsAll(seen)||!table.equals("prepared_answer_upload_bundle")&&!answers.equals(seen))throw invalid();
        }
        return snapshot(objects);
    }
    private record AnswerHeader(String client,int seconds){}
    private record SegmentHeader(String id,int ordinal,String mime){}
    /** Deterministic incremental physical binding digest, not answer contents.
     * Bounded by existing50 questions/10k chunks each; no giant JSON buffer. */
    public static Snapshot snapshot(List<Item> objects){
        if(objects==null||objects.isEmpty()||objects.size()>500050)throw invalid();
        var sorted=new ArrayList<>(objects);if(sorted.stream().anyMatch(Objects::isNull))throw invalid();sorted.sort(Comparator.comparing(Item::key));
        var hash=digest();var keys=new HashSet<String>();long bytes=0;field(hash,"prepared-original-audio-inventory-v1");
        for(var item:sorted){
            if(!keys.add(item.key()))throw invalid();bytes=Math.addExact(bytes,item.byteSize());
            for(String value:List.of(item.kind(),item.key(),Long.toString(item.byteSize()),item.sha256()))field(hash,value);
        }
        return new Snapshot(sorted,bytes,HexFormat.of().formatHex(hash.digest()));
    }
    private static void field(MessageDigest digest,String text){
        byte[] value=text.getBytes(StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(value.length).array());digest.update(value);
    }
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("原录音清单摘要算法不可用");}}
    private static boolean validWave(PreparedWaveAudio.Verified p){
        return p!=null&&p.sha256()!=null&&p.sha256().matches("[a-f0-9]{64}")&&p.byteSize()>=46&&p.byteSize()<=67108864
            &&p.sampleRate()==16000&&p.channels()==1&&p.dataOffset()==44&&p.dataSize()==p.byteSize()-44&&p.dataSize()%2==0
            &&p.durationMs()>0&&p.durationMs()==(p.dataSize()/2*1000+15999)/16000;
    }
    private static IllegalArgumentException invalidItem(){return new IllegalArgumentException("原录音物理对象绑定无效");}
    private static IllegalStateException invalid(){return new IllegalStateException("原录音物理对象清单无法完整核验，原数据保持保留");}
}
