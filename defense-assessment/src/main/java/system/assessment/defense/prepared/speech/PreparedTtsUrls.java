package system.assessment.defense.prepared.speech;

import java.net.URI;
import java.util.Set;

/** Only documented provider-result buckets. Never accept arbitrary teacher-supplied download URLs. */
public final class PreparedTtsUrls {
    private PreparedTtsUrls(){}
    private static final Set<String> HOSTS=Set.of("dashscope-result-bj.oss-cn-beijing.aliyuncs.com","dashscope-result-wlcb.oss-cn-wulanchabu.aliyuncs.com");
    public static URI https(String value){
        try {
            if(value==null || value.length()>4096)throw invalid();
            var uri=URI.create(value);String path=uri.getRawPath();
            if(!Set.of("http","https").contains(uri.getScheme()) || !HOSTS.contains(uri.getHost()) || uri.getUserInfo()!=null || uri.getPort()!=-1
                    || uri.getFragment()!=null || path==null || !path.matches("/[a-zA-Z0-9/_.,-]+\\.wav") || path.contains("/../") || path.contains("/./"))throw invalid();
            // Preserve the exact raw path and signature query; scheme is not part of the OSS resource signature.
            return "http".equals(uri.getScheme())?URI.create("https:"+value.substring(5)):uri;
        }catch(RuntimeException error){throw invalid();}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("播报下载地址未通过安全校验");}
}
