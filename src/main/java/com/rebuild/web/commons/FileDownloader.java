/*!
Copyright (c) REBUILD <https://getrebuild.com/> and/or its owners. All rights reserved.

rebuild is dual-licensed under commercial and open source licenses (GPLv3).
See LICENSE and COMMERCIAL in the project root for license information.
*/

package com.rebuild.web.commons;

import cn.devezhao.commons.CodecUtils;
import cn.devezhao.commons.web.ServletUtils;
import cn.devezhao.persist4j.engine.ID;
import com.qiniu.storage.model.FileInfo;
import com.rebuild.api.user.AuthTokenManager;
import com.rebuild.core.Application;
import com.rebuild.core.privileges.UserService;
import com.rebuild.core.support.ConfigurationItem;
import com.rebuild.core.support.RebuildConfiguration;
import com.rebuild.core.support.i18n.Language;
import com.rebuild.core.support.integration.QiniuCloud;
import com.rebuild.utils.AppUtils;
import com.rebuild.utils.CommonsUtils;
import com.rebuild.utils.ImageView2;
import com.rebuild.utils.OkHttpUtils;
import com.rebuild.utils.RbAssert;
import com.rebuild.web.BaseController;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 文件下载/查看
 *
 * @author devezhao
 * @since 01/03/2019
 */
@Slf4j
@Controller
@RequestMapping("/filex/")
public class FileDownloader extends BaseController {

    public static final String INLINE_FORCE = "0";

    @GetMapping("img/**")
    public void viewImg(HttpServletRequest request, HttpServletResponse response) throws IOException {
        RbAssert.isAllow(checkUser(request), "Unauthorized access");

        String filePath = request.getRequestURI();
        filePath = filePath.split("/filex/img/")[1];
        filePath = CodecUtils.urlDecode(filePath);

        if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
            response.sendRedirect(filePath);
            return;
        }

        final boolean temp = getBoolParameter(request, "temp");
        final boolean local = temp || getBoolParameter(request, "local");  // 强制本地

        String imageView2 = request.getQueryString();
        if (imageView2 != null && imageView2.contains("imageView2/")) {
            imageView2 = "imageView2/" + imageView2.split("imageView2/")[1].split("&")[0];

            // svg/webp does not support
            if (filePath.toLowerCase().endsWith(".svg") || filePath.toLowerCase().endsWith(".webp")) {
                imageView2 = null;
            }
        } else {
            imageView2 = null;
        }

        final int cacheTime = 60;
        ServletUtils.addCacheHead(response, cacheTime);

        // Local storage || temp || local
        if (!QiniuCloud.instance().available() || local) {
            String fileName = QiniuCloud.parseFileName(filePath);
            String mimeType = request.getServletContext().getMimeType(fileName);
            if (mimeType != null) {
                response.setContentType(mimeType);
            }

            final ImageView2 iv2 = imageView2 == null ? null : new ImageView2(imageView2);

            // 使用原图
            if (iv2 == null || iv2.getWidth() <= 0 || iv2.getWidth() >= ImageView2.ORIGIN_WIDTH) {
                writeLocalFile(filePath, temp, response);
            }
            // 粗略图
            else {
                filePath = checkFilePath(filePath);
                File img = temp ? RebuildConfiguration.getFileOfTemp(filePath) : RebuildConfiguration.getFileOfData(filePath);
                if (!img.exists()) {
                    response.setHeader("Content-Disposition", StringUtils.EMPTY);  // Clean download
                    response.sendError(HttpStatus.NOT_FOUND.value());
                    return;
                }

                writeLocalFile(iv2.thumbQuietly(img), response);
            }

        } else {
            // 特殊字符文件名
            String[] path = filePath.split("/");
            path[path.length - 1] = CodecUtils.urlEncode(path[path.length - 1]);
            path[path.length - 1] = path[path.length - 1].replace("+", "%20");
            filePath = StringUtils.join(path, "/");

            if (imageView2 != null) {
                filePath += "?" + imageView2;
            }

            String privateUrl = QiniuCloud.instance().makeUrl(filePath, (int) (cacheTime * 60 * 1.2));
            response.sendRedirect(privateUrl);
        }
    }

    @GetMapping(value = {"download/**", "access/**"})
    public void download(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String filePath = request.getRequestURI();

        // 共享查看
        if (request.getRequestURI().contains("/filex/access/")) {
            String e = getParameter(request, "e");
            if (!checkEsign(e)) {
                response.sendError(HttpStatus.FORBIDDEN.value(), Language.L("分享的文件已过期"));
                return;
            }

            filePath = filePath.split("/filex/access/")[1];
        } else {
            RbAssert.isAllow(checkUser(request), "Unauthorized access");
            filePath = filePath.split("/filex/download/")[1];
        }

        String attname = getParameter(request, "attname");
        if (StringUtils.isBlank(attname)) {
            attname = QiniuCloud.parseFileName(filePath);
            attname = CodecUtils.urlDecode(attname);
        }

        boolean temp = getBoolParameter(request, "temp");

        ServletUtils.setNoCacheHeaders(response);

        if (QiniuCloud.instance().available() && !temp) {
            String privateUrl = QiniuCloud.instance().makeUrl(filePath);
            if (!INLINE_FORCE.equals(attname)) privateUrl += "&attname=" + CodecUtils.urlEncode(attname);
            response.sendRedirect(privateUrl);
        } else {

            // V34 PDF/HTML 可直接预览
            boolean inline = (filePath.toLowerCase().endsWith(".pdf") || filePath.toLowerCase().endsWith(".html"))
                    && (request.getRequestURI().contains("/filex/access/") || INLINE_FORCE.equals(attname));

            setDownloadHeaders(request, response, attname, inline);
            writeLocalFile(filePath, temp, response);
        }
    }

    @GetMapping(value = "read-raw")
    public void readRawText(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String filePath = getParameterNotNull(request, "url");
        final String charset = getParameter(request, "charset", AppUtils.UTF8);
        final int cut = getIntParameter(request, "cut");  // MB

        if (CommonsUtils.isExternalUrl(filePath)) {
            String text = OkHttpUtils.get(filePath, null, charset);
            ServletUtils.write(response, text);
            return;
        }

        RbAssert.isAllow(checkUser(request), "Unauthorized access");

        String text;
        if (QiniuCloud.instance().available()) {
            FileInfo fi = QiniuCloud.instance().stat(filePath);
            if (fi == null) {
                text = "ERROR:FILE_NOT_EXISTS";
            } else if (cut > 0 && fi.fsize / 1024 / 1024 > cut) {
                text = "ERROR:FILE_TOO_LARGE";
            } else {
                text = OkHttpUtils.get(QiniuCloud.instance().makeUrl(filePath), null, charset);
            }

        } else {
            // Local storage
            filePath = checkFilePath(filePath);
            File file = RebuildConfiguration.getFileOfData(filePath);

            if (!file.exists()) {
                text = "ERROR:FILE_NOT_EXISTS";
            } else if (cut > 0 && FileUtils.sizeOf(file) / 1024 / 1024 > cut) {
                text = "ERROR:FILE_TOO_LARGE";
            } else {
                text = FileUtils.readFileToString(file, charset);
            }
        }

        ServletUtils.write(response, text);
    }

    @GetMapping(value = "proxy-download")
    public void proxyDownload(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String fileUrl = request.getParameter("url");
        fileUrl = CodecUtils.urlDecode(fileUrl);

        File tmp = QiniuCloud.getStorageFile(fileUrl);
        writeLocalFile(tmp, response);
    }

    /**
     * 独立认证检测
     *
     * @param request
     * @return
     */
    protected static boolean checkUser(HttpServletRequest request) {
        // 1.session
        ID user = AppUtils.getRequestUser(request);

        // 2.accessToken
        if (user == null) {
            String accessToken = request.getParameter(AppUtils.URL_AUTHTOKEN);
            user = accessToken == null ? null : AuthTokenManager.verifyToken(accessToken);
        }
        // 3.csrfToken
        if (user == null) {
            String csrfToken = request.getParameter(AppUtils.URL_CSRFTOKEN);
            user = csrfToken == null ? null : AuthTokenManager.verifyToken(csrfToken);
        }
        // 4.onceToken
        if (user == null) {
            String onceToken = request.getParameter(AppUtils.URL_ONCETOKEN);
            user = onceToken == null ? null : AuthTokenManager.verifyToken(onceToken);
        }
        // 5. UnsafeImgAccess
        if (user == null && RebuildConfiguration.getBool(ConfigurationItem.UnsafeImgAccess)) {
            String unsafe = request.getParameter("_UNSAFEIMGACCESS");
            user = NumberUtils.isNumber(unsafe) ? UserService.SYSTEM_USER : null;
        }

        return user != null;
    }

    // @see FileShareController#makePublicUrl
    private static boolean checkEsign(String e) {
        String check = e == null ? null : Application.getCommonsCache().get(e);
        return "rb".equalsIgnoreCase(check);
    }

    private static String checkFilePath(String filepath) {
        filepath = CodecUtils.urlDecode(filepath);
        filepath = filepath.replace("\\", "/");

        CommonsUtils.checkFilePathAttack(filepath);
        if (filepath.startsWith("_log/") || filepath.contains("/_log/")
                || filepath.startsWith("_backups/") || filepath.contains("/_backups/")) {
            throw new SecurityException("Attack path detected : " + filepath);
        }
        return filepath;
    }

    private static boolean writeLocalFile(String filePath, boolean temp, HttpServletResponse response) throws IOException {
        filePath = checkFilePath(filePath);
        File file = temp ? RebuildConfiguration.getFileOfTemp(filePath) : RebuildConfiguration.getFileOfData(filePath);
        return writeLocalFile(file, response);
    }

    /**
     * 本地文件下载
     *
     * @param file
     * @param response
     * @return
     * @throws IOException
     */
    public static boolean writeLocalFile(File file, HttpServletResponse response) throws IOException {
        if (file == null || !file.exists()) {
            response.setHeader("Content-Disposition", StringUtils.EMPTY);  // Clean download
            response.sendError(HttpStatus.NOT_FOUND.value());
            return false;
        }

        long size = FileUtils.sizeOf(file);
        response.setHeader("Content-Length", String.valueOf(size));

        try (InputStream fis = Files.newInputStream(file.toPath())) {
            return writeStream(fis, response);
        }
    }

    /**
     * @param is
     * @param response
     * @return
     * @throws IOException
     */
    public static boolean writeStream(InputStream is, HttpServletResponse response) throws IOException {
        response.setContentLength(is.available());

        OutputStream os = response.getOutputStream();
        int count;
        byte[] buffer = new byte[1024 * 1024];
        while ((count = is.read(buffer)) != -1) {
            os.write(buffer, 0, count);
        }
        os.flush();
        return true;
    }

    /**
     * 设置下载 Headers
     *
     * @param request
     * @param response
     * @param attname
     * @param inline
     */
    public static void setDownloadHeaders(HttpServletRequest request, HttpServletResponse response, String attname, boolean inline) {
        // 特殊字符处理
        attname = attname.replace(" ", "-");
        attname = attname.replace("%", "-");

        // 火狐 Safari 中文名乱码问题
        String UA = StringUtils.defaultIfBlank(request.getHeader("user-agent"), "").toUpperCase();
        if (UA.contains("FIREFOX") || UA.contains("SAFARI") || UA.contains("APPLEWEBKIT")) {
            attname = CodecUtils.urlDecode(attname);
            attname = new String(attname.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        }

        if (inline) {
            response.setHeader("Content-Disposition", "inline;filename=" + attname);
        } else {
            response.setHeader("Content-Disposition", "attachment;filename=" + attname);
            response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        }
    }

    /**
     * @param resp
     * @param file
     * @param attname
     * @throws IOException
     */
    public static void downloadTempFile(HttpServletResponse resp, File file, String attname) throws IOException {
        String url = String.format("/filex/download/%s?temp=yes", CodecUtils.urlEncode(file.getName()));
        if (attname != null) url += "&attname=" + CodecUtils.urlEncode(attname);
        resp.sendRedirect(AppUtils.getContextPath(url));
    }
}
