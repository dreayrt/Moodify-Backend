package com.laphuth.moodify.services;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.util.concurrent.TimeUnit;

/** A healthy release must have the binaries the upload pipeline depends on. */
@Component
public class CatalogIntegrityReadiness implements ApplicationRunner {
    private final String ffprobe;
    public CatalogIntegrityReadiness(@Value("${audio.metadata.ffprobe:ffprobe}") String ffprobe) { this.ffprobe=ffprobe; }
    @Override public void run(ApplicationArguments arguments) throws Exception {
        checkBinary(ffprobe,"-version");checkBinary("pdftotext","-v");
    }
    private void checkBinary(String command,String flag) throws Exception {
        Process probe=new ProcessBuilder(command,flag).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if(!probe.waitFor(10,TimeUnit.SECONDS)||probe.exitValue()!=0)throw new IllegalStateException("ffprobe dependency check failed");
        } finally { if(probe.isAlive())probe.destroyForcibly(); }
    }
}
