package system.assessment.defense.prepared.media;

import java.util.ArrayList;
import java.util.List;

public final class PreparedSpeechText {
    private PreparedSpeechText() {}
    /** Qwen3-TTS-Flash allows 600 characters. UTF-16 units are a conservative bound;
     * every unit of the original text is retained, including punctuation and whitespace. */
    public static List<String> split(String body) {
        if (body==null || body.isBlank() || body.length()>10000) throw new IllegalArgumentException("播报文字为空或超长");
        for (int i=0;i<body.length();i++) {
            char c=body.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i+1==body.length() || !Character.isLowSurrogate(body.charAt(++i))) throw new IllegalArgumentException("播报文字包含无效字符");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("播报文字包含无效字符");
        }
        var pieces=new ArrayList<String>();int start=0;
        while (start<body.length()) {
            int end=Math.min(start+600,body.length());
            if (Character.isHighSurrogate(body.charAt(end-1))) end--;
            if (end<body.length()) {
                for (int i=end-1;i>=start+300;i--) {
                    if ("。！？；，.!?;,\n".indexOf(body.charAt(i))>=0) { end=i+1;break; }
                }
            }
            pieces.add(body.substring(start,end));start=end;
        }
        return List.copyOf(pieces);
    }
}
