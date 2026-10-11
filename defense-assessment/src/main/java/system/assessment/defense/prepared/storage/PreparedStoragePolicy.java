package system.assessment.defense.prepared.storage;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Independent prepared storage; configuration is not proof that a bucket has been tested. */
@Component
public class PreparedStoragePolicy {
    public record Availability(boolean enabled,String reason) {}
    public record Snapshot(String endpoint,String bucket,String deploymentId) {}
    private final Environment env;
    public PreparedStoragePolicy(Environment env){this.env=env;}
    public Availability availability(){
        try {snapshot();return new Availability(true,"存储配置齐备，仍须真实权限与读写核验");}
        catch(IllegalArgumentException error){return new Availability(false,"独立私有音频存储尚未启用或配置未通过校验");}
    }
    public Snapshot snapshot(){
        if(!"true".equals(env.getProperty("prepared.storage.enabled","false")))throw invalid();
        String endpoint=required("endpoint"),bucket=required("bucket");
        if(!"https://oss-cn-beijing.aliyuncs.com".equals(endpoint)
                || !bucket.matches("prepared-interview-[a-z0-9][a-z0-9-]{0,43}") || bucket.endsWith("-"))throw invalid();
        String deployment=env.getProperty("prepared.deployment-id","");
        if(!deployment.matches("prepared-[a-z0-9][a-z0-9-]{0,47}") || deployment.endsWith("-"))throw invalid();
        accessKeyId();accessKeySecret();return new Snapshot(endpoint,bucket,deployment);
    }
    public String accessKeyId(){return credential("access-key-id");}
    public String accessKeySecret(){return credential("access-key-secret");}
    private String credential(String field){String value=required(field);
        if(value.length()>512 || value.chars().anyMatch(Character::isWhitespace) || value.startsWith("unused-")
                || value.equalsIgnoreCase("change-me"))throw invalid();return value;}
    private String required(String field){String value=env.getProperty("prepared.storage."+field,"");
        if(value.isBlank() || value.length()>1000)throw invalid();return value;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("独立私有音频存储配置无效");}
}
