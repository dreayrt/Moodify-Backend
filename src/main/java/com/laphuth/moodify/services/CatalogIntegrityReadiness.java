package com.laphuth.moodify.services;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.util.concurrent.TimeUnit;

/** A healthy release must have the explicit schema migration and its upload dependency. */
@Component
public class CatalogIntegrityReadiness implements ApplicationRunner {
    private final JdbcTemplate sql;
    private final String ffprobe;
    public CatalogIntegrityReadiness(JdbcTemplate sql,@Value("${audio.metadata.ffprobe:ffprobe}") String ffprobe) { this.sql=sql;this.ffprobe=ffprobe; }
    @Override public void run(ApplicationArguments arguments) throws Exception {
        Integer ready=sql.queryForObject("SELECT COUNT(*) FROM catalog_schema_versions WHERE version=1",Integer.class);
        if(ready==null||ready!=1)throw new IllegalStateException("Catalog schema migration v1 is required before starting this release");
        checkBinary(ffprobe,"-version");checkBinary("pdftotext","-v");
    }
    private void checkBinary(String command,String flag) throws Exception {
        Process probe=new ProcessBuilder(command,flag).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if(!probe.waitFor(10,TimeUnit.SECONDS)||probe.exitValue()!=0)throw new IllegalStateException("ffprobe dependency check failed");
        } finally { if(probe.isAlive())probe.destroyForcibly(); }
    }
}
