package system.assessment.defense.prepared.attempt;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import system.assessment.defense.infrastructure.config.BaseResult;
import system.assessment.defense.infrastructure.exception.BusinessException;
import java.io.*;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Metadata and streaming chunks have separate bounds/admission; media is never buffered into a JSON byte array. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PreparedStudentBodyLimitFilter extends OncePerRequestFilter {
    public static final int MAX_BYTES = 2 * 1024 * 1024 + 4096;
    public static final int SMALL_COMMAND_MAX_BYTES = 64 * 1024;
    private final ObjectMapper json;
    private final Semaphore parsing;
    private final Semaphore smallCommands;
    private final Semaphore uploads=new Semaphore(4,true);
    private static final String UUID_PATH="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final java.util.regex.Pattern CHUNK_PATH=java.util.regex.Pattern.compile("/backend/prepared-interviews/[1-9][0-9]{0,9}/attempts/"+UUID_PATH+"/answers/"+UUID_PATH+"/chunks/"+UUID_PATH+"/[0-9]{1,4}");
    private static final java.util.regex.Pattern CHUNKS_QUERY=java.util.regex.Pattern.compile("/backend/prepared-interviews/[1-9][0-9]{0,9}/attempts/"+UUID_PATH+"/answers/"+UUID_PATH+"/chunks");
    private static final String BUNDLE_BASE="/backend/prepared-interviews/[1-9][0-9]{0,9}/attempts/"+UUID_PATH+"/answers/"+UUID_PATH+"/upload-bundles";
    private static final java.util.regex.Pattern BUNDLE_PREPARE=java.util.regex.Pattern.compile(BUNDLE_BASE);
    private static final java.util.regex.Pattern BUNDLE_PATH=java.util.regex.Pattern.compile(BUNDLE_BASE+"/[a-f0-9]{64}");
    public static boolean isChunkRoute(String path){return CHUNK_PATH.matcher(path).matches()||CHUNKS_QUERY.matcher(path).matches()||BUNDLE_PREPARE.matcher(path).matches()||BUNDLE_PATH.matcher(path).matches();}

    @Autowired
    public PreparedStudentBodyLimitFilter(ObjectMapper json) { this(json, 16, 128); }
    public PreparedStudentBodyLimitFilter(ObjectMapper json, int concurrentCommands) {
        this(json,concurrentCommands,128);
    }
    public PreparedStudentBodyLimitFilter(ObjectMapper json, int concurrentCommands,int concurrentSmallCommands) {
        if (concurrentCommands < 1 || concurrentCommands > 16) throw new IllegalArgumentException("作答解析并发配置无效");
        if (concurrentSmallCommands < 1 || concurrentSmallCommands > 128) throw new IllegalArgumentException("小命令并发配置无效");
        this.json = json;
        this.parsing = new Semaphore(concurrentCommands, true);
        this.smallCommands = new Semaphore(concurrentSmallCommands, true);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        return !(path.startsWith("/backend/prepared-interviews/") || path.startsWith("/backend/prepared-interviews;")
                || path.startsWith("/backend/prepared-reviews/") || path.startsWith("/backend/prepared-reviews;"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Preserve Sa-Token's forbidden matrix-path policy, with a real JSON error rather than its plain-text 200.
        if (UrlPathHelper.defaultInstance.decodeRequestString(request, request.getRequestURI()).contains(";")) {
            reject(response, 400, "预置面试接口不接受路径参数");
            return;
        }
        if (!("POST".equals(request.getMethod()) || "PUT".equals(request.getMethod()) || "PATCH".equals(request.getMethod()))) {
            chain.doFilter(request, response);
            return;
        }
        String encoding = request.getHeader("Content-Encoding");
        String type = request.getContentType();
        String mediaType = type == null ? "" : type.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        String path=UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if("PUT".equals(request.getMethod())&&(CHUNK_PATH.matcher(path).matches()||BUNDLE_PATH.matcher(path).matches())) {
            streamChunk(request,response,chain,encoding,mediaType,BUNDLE_PATH.matcher(path).matches()?8388608:2097152);
            return;
        }
        if ((encoding != null && !"identity".equalsIgnoreCase(encoding))
                || !(mediaType.equals("application/json") || (mediaType.startsWith("application/") && mediaType.endsWith("+json")))) {
            reject(response, 415, "预置面试命令仅接受未压缩的 JSON");
            return;
        }
        int metadataLimit=path.startsWith("/backend/prepared-reviews/")?16384:BUNDLE_PREPARE.matcher(path).matches()?32768:MAX_BYTES;
        if (request.getContentLengthLong() > metadataLimit) {
            reject(response, 413, "命令内容过大，请保留原内容并联系教师");
            return;
        }
        // Keep large/unknown bodies bounded separately from small original commands.
        // 128 small <=64KiB buffers add at most 8MiB, NOT 128 two-MiB manifests.
        // A declared small body is also bounded by actual bytes; unknown lengths
        // retain the original 16-command large lane and cannot bypass its cap.
        long declared=request.getContentLengthLong();
        boolean small=declared>=0&&declared<=Math.min(metadataLimit,SMALL_COMMAND_MAX_BYTES);
        var admission=small?smallCommands:parsing;
        int bufferLimit=small?Math.min(metadataLimit,SMALL_COMMAND_MAX_BYTES):metadataLimit;
        boolean admitted;
        try { admitted = admission.tryAcquire(250, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); admitted = false; }
        if (!admitted) {
            response.setHeader("Retry-After", "1");
            reject(response, 503, "确认暂时繁忙，请保留原内容并重试原请求");
            return;
        }
        try { parseBounded(request, response, chain,bufferLimit); }
        finally { admission.release(); }
    }

    private void streamChunk(HttpServletRequest request,HttpServletResponse response,FilterChain chain,String encoding,String mediaType,int limit)
            throws ServletException,IOException {
        if((encoding!=null&&!"identity".equalsIgnoreCase(encoding))||!"application/octet-stream".equals(mediaType)) {
            reject(response,415,"录音分片仅接受未压缩的二进制原内容");return;
        }
        if(request.getContentLengthLong()>limit){reject(response,413,"录音传输内容过大，请保留原内容");return;}
        boolean admitted;
        try{admitted=uploads.tryAcquire(250,TimeUnit.MILLISECONDS);}catch(InterruptedException failure){Thread.currentThread().interrupt();admitted=false;}
        if(!admitted){response.setHeader("Retry-After","1");reject(response,503,"录音上传暂时繁忙，本机录音不受影响，请稍后补传");return;}
        try {
            var input=request.getInputStream();
            var bounded=new ServletInputStream(){
                int count;
                private int accepted(int read){if(read>0&&((count+=read)>limit))throw new BusinessException(413,"录音传输内容过大，请保留原内容");return read;}
                @Override public int read()throws IOException {int value=input.read();if(value!=-1)accepted(1);return value;}
                @Override public int read(byte[] buffer,int offset,int length)throws IOException {java.util.Objects.checkFromIndexSize(offset,length,buffer.length);if(length==0)return 0;return accepted(input.read(buffer,offset,Math.min(length,limit+1-count)));}
                @Override public boolean isFinished(){return input.isFinished();}
                @Override public boolean isReady(){return input.isReady();}
                @Override public void setReadListener(ReadListener listener){throw new IllegalStateException("仅支持同步有界录音上传");}
            };
            chain.doFilter(new HttpServletRequestWrapper(request){@Override public ServletInputStream getInputStream(){return bounded;}},response);
        }catch(BusinessException failure){if(failure.getCode()!=413)throw failure;reject(response,413,"录音分片过大，请保留原内容");}
        finally{uploads.release();}
    }

    private void parseBounded(HttpServletRequest request, HttpServletResponse response, FilterChain chain,int limit)
            throws ServletException, IOException {
        // Content-Length can be missing or misleading; never trust it as the actual bound.
        byte[] bytes = request.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit) {
            reject(response, 413, "命令内容过大，请保留原内容并联系教师");
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] target, int offset, int length) { return input.read(target, offset, length); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("仅支持同步 JSON 读取"); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8)); }
        }, response);
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), BaseResult.error(status, message));
    }
}
