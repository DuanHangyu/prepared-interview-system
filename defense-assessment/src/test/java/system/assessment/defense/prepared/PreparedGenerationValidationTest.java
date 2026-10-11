package system.assessment.defense.prepared;

import org.junit.jupiter.api.Test;
import system.assessment.defense.prepared.generation.PreparedGeneration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PreparedGenerationValidationTest {
    final String key=UUID.randomUUID().toString();
    @Test void generationRejectsEveryInvalidBoundaryBeforeCreatingAPaidJob() {
        new PreparedGeneration.Command(0L,key,"要求",1,1,1).validate();
        new PreparedGeneration.Command(1L,key,"要求",20,10000,1800).validate();
        for(var cmd:List.of(
                new PreparedGeneration.Command(null,key,"要求",1,10,120),
                new PreparedGeneration.Command(-1L,key,"要求",1,10,120),
                new PreparedGeneration.Command(Long.MAX_VALUE,key,"要求",1,10,120),
                new PreparedGeneration.Command(0L,key,null,1,10,120),
                new PreparedGeneration.Command(0L,key," ",1,10,120),
                new PreparedGeneration.Command(0L,key,"x".repeat(8001),1,10,120),
                new PreparedGeneration.Command(0L,key,"要求",0,10,120),
                new PreparedGeneration.Command(0L,key,"要求",21,10,120),
                new PreparedGeneration.Command(0L,key,"要求",1,0,120),
                new PreparedGeneration.Command(0L,key,"要求",1,10001,120),
                new PreparedGeneration.Command(0L,key,"要求",1,10,0),
                new PreparedGeneration.Command(0L,key,"要求",1,10,1801)))
            assertThatThrownBy(cmd::validate).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void adoptionRequiresExplicitVersionDistinctBoundedServerSuggestionIds() {
        new PreparedGeneration.AdoptCommand(0L,key,List.of(key)).validate();
        for(var cmd:List.of(new PreparedGeneration.AdoptCommand(null,key,List.of(key)),
                new PreparedGeneration.AdoptCommand(-1L,key,List.of(key)),new PreparedGeneration.AdoptCommand(Long.MAX_VALUE,key,List.of(key)),
                new PreparedGeneration.AdoptCommand(0L,key,null),new PreparedGeneration.AdoptCommand(0L,key,List.of()),
                new PreparedGeneration.AdoptCommand(0L,key,Collections.nCopies(21,key)),
                new PreparedGeneration.AdoptCommand(0L,key,List.of(key,key)),new PreparedGeneration.AdoptCommand(0L,key,List.of("../other"))))
            assertThatThrownBy(cmd::validate).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void damagedPersistedInputFailsClosedWithoutEchoingItsContent() {
        for(String raw:List.of("{}","{\"command\":null,\"policy\":null}","{\"command\":{},\"policy\":{}}"))
            assertThatThrownBy(()->PreparedGeneration.input(raw)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->PreparedGeneration.encode(new Object())).isInstanceOf(IllegalArgumentException.class);
    }
}
