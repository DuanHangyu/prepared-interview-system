package system.assessment.defense.prepared.processing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import system.assessment.defense.infrastructure.exception.BusinessException;
import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import java.io.IOException;
import java.time.*;

/** External read/signing is outside database locks. No model invocation or original mutation. */
@Service
public class PreparedReviewAudioService {
    public record Audio(int actorId,int assessmentId,int studentId,String attemptId,String answerId,String url,String expiresAt,String serverTime,String sha256,long byteSize,long durationMs){
        @Override public String toString(){return "Audio[answerId="+answerId+", private signed URL redacted]";}
    }
    private final PreparedProcessingService processing;private final PreparedPrivateOssStore store;private final Clock clock;
    private final java.util.concurrent.Semaphore reads=new java.util.concurrent.Semaphore(2);
    @Autowired public PreparedReviewAudioService(PreparedProcessingService processing,PreparedPrivateOssStore store){this(processing,store,Clock.systemUTC());}
    public PreparedReviewAudioService(PreparedProcessingService processing,PreparedPrivateOssStore store,Clock clock){this.processing=processing;this.store=store;this.clock=clock;}
    public Audio preview(int actor,int assessment,String attempt,String answer){
        var media=processing.reviewMedia(actor,assessment,attempt,answer);var scope=media.scope();
        if(scope.assessmentId()!=assessment||!scope.attemptId().equals(attempt)||!scope.answerId().equals(answer))throw new IllegalStateException("原录音评阅范围无法核验");
        if(!reads.tryAcquire())throw new BusinessException(503,"原录音核验繁忙，请稍后重新获取授权；不要求学生重新作答");
        try {
        var io=processing.beginReviewAudioIo(actor,assessment,attempt,answer,media);
        try {
        PreparedPrivateOssStore.Preview preview;Instant expires;
        try{preview=store.previewAnswer(media);expires=Instant.parse(preview.expiresAt());var now=clock.instant();
            if(!expires.isAfter(now)||expires.isAfter(now.plusSeconds(300)))throw new IOException();
        }catch(IOException|RuntimeException failure){
            // A disappearing file can be a concurrent retirement, not a harmless
            // network failure. Fresh original permission/state wins even on IO
            // failure; no audit or signed capability is published from this path.
            processing.reviewMedia(actor,assessment,attempt,answer);
            throw new BusinessException(503,"原录音播放授权暂不可用，请稍后重新获取授权；不要求学生重新作答");
        }
        processing.recordAudioAccess(actor,assessment,attempt,answer,media,expires);
        var delivered=clock.instant();if(!expires.isAfter(delivered))throw new BusinessException(503,"播放授权已到期，请重新获取；不要求学生重新作答");
        return new Audio(actor,assessment,scope.studentId(),attempt,answer,preview.url(),expires.toString(),delivered.toString(),media.proof().sha256(),media.proof().byteSize(),media.proof().durationMs());
        }finally{processing.endAudioIo(io);}
        }finally{reads.release();}
    }
}
