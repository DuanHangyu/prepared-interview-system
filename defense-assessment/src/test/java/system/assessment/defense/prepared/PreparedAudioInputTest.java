package system.assessment.defense.prepared;
import org.junit.jupiter.api.Test;
import system.assessment.defense.prepared.audio.PreparedAudio;
import system.assessment.defense.prepared.jobs.PreparedJobInput;
import system.assessment.defense.prepared.speech.PreparedSpeech;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class PreparedAudioInputTest {
    String q=UUID.randomUUID().toString(),a=UUID.randomUUID().toString(),child=UUID.randomUUID().toString(),body="完整题目",hash=PreparedJobInput.digest(body),config="a".repeat(64);
    PreparedAudio.WholeInput input(int version,String text,String digest,List<String> children){return new PreparedAudio.WholeInput(version,q,a,text,digest,config,children);}
    @Test void wholeInputIsStrictBoundedAndRedactsItsPrivateBody(){
        var valid=input(1,body,hash,List.of(child));assertThat(PreparedAudio.whole(PreparedSpeech.encode(valid))).isEqualTo(valid);
        assertThat(valid.toString()).doesNotContain(body);assertThat(PreparedAudio.sha(config)).isEqualTo(config);
        for(var invalid:List.of(input(2,body,hash,List.of(child)),input(1,null,hash,List.of(child)),input(1,"",hash,List.of(child)),input(1,"x".repeat(10001),hash,List.of(child)),
                input(1,body,"0".repeat(64),List.of(child)),input(1,body,hash,null),input(1,body,hash,List.of()),input(1,body,hash,Collections.nCopies(51,child)),input(1,body,hash,List.of(child,child)),input(1,body,hash,List.of("../job"))))
            assertThatThrownBy(()->PreparedAudio.whole(PreparedSpeech.encode(invalid))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedAudio.sha(null)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->PreparedAudio.sha("BAD")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedAudio.whole("{}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedAudio.whole("{broken")).isInstanceOf(IllegalArgumentException.class);
    }
}
