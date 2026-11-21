package com.laphuth.moodify.services;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Local PDF text inspection catches explicitly fictional/unsigned test documents.
 * It does not authenticate signatures or establish ownership; that remains a human review.
 */
@Service
public class LicenseDocumentInspector {
    public String inspect(MultipartFile document) {
        if(document.getSize()>20L*1024*1024)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Tài liệu vượt quá 20 MB.");
        Path input=null,output=null;
        try(var stream=document.getInputStream()) {
            byte[] signature=stream.readNBytes(5);
            if(!new String(signature,StandardCharsets.US_ASCII).equals("%PDF-"))return "MANUAL_REVIEW_REQUIRED";
            input=Files.createTempFile("moodify-license-",".pdf");output=Files.createTempFile("moodify-license-",".txt");
            try(var source=document.getInputStream()) { Files.copy(source,input,StandardCopyOption.REPLACE_EXISTING); }
            Process process=new ProcessBuilder("pdftotext","-enc","UTF-8",input.toString(),output.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                if(!process.waitFor(15,TimeUnit.SECONDS))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Đọc tài liệu quá lâu.");
                if(process.exitValue()!=0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Không đọc được tài liệu PDF.");
            } finally { if(process.isAlive())process.destroyForcibly(); }
            if(Files.size(output)>1024*1024)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Tài liệu vượt giới hạn nội dung.");
            String text=Files.readString(output,StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            return hasSimulationMarkers(text)?"SIMULATION_DETECTED":"TEXT_CHECKED";
        } catch(java.io.IOException error) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Máy chủ chưa kiểm tra được tài liệu.",error); }
        catch(InterruptedException error) { Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Đọc tài liệu bị gián đoạn."); }
        finally { for(Path p:new Path[]{input,output})if(p!=null)try { Files.deleteIfExists(p); } catch(java.io.IOException ignored) {} }
    }
    static boolean hasSimulationMarkers(String text) {
        String normalized=text.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
        return normalized.contains("mẫu thử nghiệm")||normalized.contains("dữ liệu mô phỏng")||normalized.contains("giả định")
            ||normalized.contains("chưa ký kết")||normalized.contains("simulated")||normalized.contains("fictional");
    }
}
