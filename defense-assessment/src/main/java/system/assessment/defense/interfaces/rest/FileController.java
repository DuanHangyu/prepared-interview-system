package system.assessment.defense.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import system.assessment.defense.application.dto.UploadDTO;
import system.assessment.defense.application.manage.OssManager;
import system.assessment.defense.application.service.FileAccessControlService;
import system.assessment.defense.application.service.FileSecurityService;
import system.assessment.defense.application.service.ManagedUploadService;

import java.io.IOException;

/**
 * @USER taoHouChao
 * @DATE 07:43 2025/8/13
 */
@RestController
@RequestMapping("/file")
@Tag(name = "文件管理", description = "文件管理")
@SaCheckRole(value = {"STUDENT", "TEACHER", "ADMIN"}, mode = SaMode.OR)
public class FileController {

    @Resource
    private OssManager ossClientUtils;

    @Resource
    private FileSecurityService fileSecurityService;

    @Resource
    private FileAccessControlService fileAccessControlService;

    @Resource
    private ManagedUploadService managedUploadService;

    @PostMapping("/upload")
    @Operation(summary = "上传文件")
    public UploadDTO uploadFile(@RequestParam("file") MultipartFile file) {
        String fileUrl = managedUploadService.upload(file, StpUtil.getLoginIdAsInt());
        String signedUrl = ossClientUtils.getSignedUrl(fileUrl);
        return new UploadDTO(signedUrl, fileUrl, file.getOriginalFilename());
    }

    @GetMapping("/signed-url")
    @Operation(summary = "获取文件签名URL")
    public String getSignedUrl(@RequestParam("url") String url){
        fileSecurityService.requireSafePdfKey(url);
        fileAccessControlService.requireReadAccess(StpUtil.getLoginIdAsInt(), url);
        return ossClientUtils.getSignedUrl(url);
    }

    @GetMapping("/preview")
    @Operation(summary = "预览文件")
    public void preview(@RequestParam("fileUrl") String fileUrl,
                        HttpServletRequest request,
                        HttpServletResponse response) throws IOException {
        fileSecurityService.requireSafePdfKey(fileUrl);
        fileAccessControlService.requireReadAccess(StpUtil.getLoginIdAsInt(), fileUrl);
        ossClientUtils.preview(fileUrl, request, response);
    }
}
