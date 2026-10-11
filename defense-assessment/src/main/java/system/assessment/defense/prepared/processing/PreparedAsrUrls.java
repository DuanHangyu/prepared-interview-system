package system.assessment.defense.prepared.processing;

import system.assessment.defense.prepared.storage.PreparedPrivateOssStore;
import java.net.URI;
import java.util.Set;

/** Exact original private input, and one pinned Beijing result bucket. No arbitrary URLs. */
public final class PreparedAsrUrls {
    private PreparedAsrUrls(){}
    public static URI input(String value,PreparedPrivateOssStore.StoredAnswer stored){try{
        var uri=parse(value);String host=uri.getHost(),key=stored.objectKey();String[] parts=key.split("/");
        if(!"https".equals(uri.getScheme())||host==null||!host.matches("prepared-interview-[a-z0-9][a-z0-9-]{0,43}\\.oss-cn-beijing\\.aliyuncs\\.com")
            ||parts.length<2||!stored.scope().key(parts[1]).equals(key)||!("/"+key).equals(uri.getRawPath()))throw invalid();return uri;
    }catch(RuntimeException e){throw invalid();}}
    public static URI result(String value){try{
        var uri=parse(value);String path=uri.getRawPath();
        if(!Set.of("http","https").contains(uri.getScheme())||!"dashscope-result-bj.oss-cn-beijing.aliyuncs.com".equals(uri.getHost())||path==null
            ||!path.replaceAll("%3[Aa]",":").matches("/[a-zA-Z0-9/_.,:-]+\\.json")||path.contains("/../")||path.contains("/./"))throw invalid();
        // OSS signs the resource, not the scheme. Preserve path/query, explicitly upgrade this pinned host.
        return uri.getScheme().equals("http")?URI.create("https:"+value.substring(5)):uri;
    }catch(RuntimeException e){throw invalid();}}
    private static URI parse(String value){if(value==null||value.length()>4096)throw invalid();var uri=URI.create(value);
        if(uri.getUserInfo()!=null||uri.getPort()!=-1||uri.getFragment()!=null||uri.getRawQuery()==null||uri.getRawQuery().isBlank())throw invalid();return uri;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("文件转写地址未通过安全校验");}
}
