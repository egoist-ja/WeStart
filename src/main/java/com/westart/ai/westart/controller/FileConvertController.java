package com.westart.ai.westart.controller;

import com.westart.ai.westart.util.FileFormatConverter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/convert")
public class FileConvertController {

    @PostMapping
    public ResponseEntity<?> convert(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "target", defaultValue = "wav") String target) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "文件不能为空"));
        }

        try {
            String sourceMime = file.getContentType();
            if (sourceMime == null) {
                String name = file.getOriginalFilename();
                if (name != null) {
                    String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase();
                    sourceMime = switch (ext) {
                        case "mp3" -> "audio/mpeg";
                        case "m4a" -> "audio/mp4";
                        case "wav" -> "audio/wav";
                        case "pdf" -> "application/pdf";
                        case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                        default -> null;
                    };
                }
            }
            if (sourceMime == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "无法识别文件类型"));
            }

            byte[] result;
            String resultMime;
            String resultExt;

            switch (target) {
                case "wav" -> {
                    result = FileFormatConverter.toWav(file.getBytes(), sourceMime);
                    resultMime = "audio/wav";
                    resultExt = ".wav";
                }
                case "pdf" -> {
                    result = FileFormatConverter.toPdf(file.getBytes(), sourceMime);
                    resultMime = "application/pdf";
                    resultExt = ".pdf";
                }
                case "docx" -> {
                    result = FileFormatConverter.toDocx(file.getBytes(), sourceMime);
                    resultMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                    resultExt = ".docx";
                }
                default -> {
                    return ResponseEntity.badRequest().body(Map.of("error", "不支持的目标格式: " + target));
                }
            }

            if (result == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "转换失败，不支持的格式组合"));
            }

            String filename = file.getOriginalFilename();
            if (filename == null) filename = "output";
            int dot = filename.lastIndexOf('.');
            if (dot > 0) filename = filename.substring(0, dot);
            filename += resultExt;

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType(resultMime))
                    .body(result);

        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "转换失败: " + e.getMessage()));
        }
    }
}
