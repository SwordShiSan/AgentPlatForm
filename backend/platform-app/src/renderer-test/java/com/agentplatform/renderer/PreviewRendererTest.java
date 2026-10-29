package com.agentplatform.renderer;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Conversion resource/sandbox proof is separate; these are deterministic parsing boundary assertions. */
class PreviewRendererTest {
    @TempDir Path temporary;
    private String sha(Path source)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));}
    private Map<String,String> render(Path source,String extension,String format)throws Exception {
        Path out=Files.createTempDirectory(temporary,"output-");return PreviewRendererMain.render(source,out,extension,format,sha(source));
    }
    private void rejects(String code,Path source,String extension,String format)throws Exception {
        var error=assertThrows(PreviewRendererMain.Rejected.class,()->render(source,extension,format));assertEquals(code,error.code);
    }
    @Test void emptyAndUnicodeTextReferenceTheOriginalBytes()throws Exception {
        Path source=temporary.resolve("source");Files.writeString(source,"");assertEquals("0",render(source,"txt","AUTO").get("bytes"));
        Files.writeString(source,"\ufeff中文🙂\r\n");var result=render(source,"txt","TEXT");assertEquals("true",result.get("sourceReferenced"));assertEquals(sha(source),result.get("sha256"));
    }
    @Test void invalidUtf8AndBinaryDoNotBecomeText()throws Exception {
        Path source=temporary.resolve("source");Files.write(source,new byte[]{(byte)0xc3,0x28});rejects("PREVIEW_TEXT_ENCODING_UNSUPPORTED",source,"txt","AUTO");
        Files.write(source,new byte[]{65,0,66});rejects("PREVIEW_TEXT_BINARY_REJECTED",source,"txt","AUTO");
    }
    @Test void textLimitIsActualBytes()throws Exception {
        Path source=temporary.resolve("source");Files.write(source,new byte[2097153]);rejects("PREVIEW_TEXT_TOO_LARGE",source,"txt","AUTO");
    }
    @Test void sourceIdentityMustMatch()throws Exception {
        Path source=temporary.resolve("source");Files.writeString(source,"hello");
        var rejected=assertThrows(PreviewRendererMain.Rejected.class,()->PreviewRendererMain.render(source,temporary,"txt","TEXT","0".repeat(64)));
        assertEquals("PREVIEW_INPUT_HASH_MISMATCH",rejected.code);
    }
    @Test void htmlIsSourceByDefaultAndExplicitStaticOutputRemovesActiveContent()throws Exception {
        Path source=temporary.resolve("source");Files.writeString(source,"<h1>中文</h1><script>fetch('/me')</script><img src='https://example.test/x'><iframe src='/app'></iframe><p onclick='steal()'>hello</p>");
        assertEquals("TEXT",render(source,"html","AUTO").get("format"));
        Path out=Files.createDirectory(temporary.resolve("html"));var result=PreviewRendererMain.render(source,out,"html","HTML_STATIC",sha(source));
        String content=Files.readString(out.resolve("content.html"));assertEquals("HTML_STATIC",result.get("format"));assertTrue(content.contains("中文"));
        for(String denied:List.of("<script","<img","<iframe","onclick","example.test","fetch("))assertFalse(content.contains(denied),denied);
        assertTrue(content.contains("default-src 'none'"));
    }
    @Test void svgCanOnlyBeReadAsText()throws Exception {
        Path source=temporary.resolve("source");Files.writeString(source,"<svg onload='alert(1)'/>");
        assertEquals("TEXT",render(source,"svg","AUTO").get("format"));rejects("PREVIEW_FORMAT_UNSUPPORTED",source,"svg","IMAGE");
    }
    @Test void nativePdfIsValidatedAndReferencedWithoutCopy()throws Exception {
        Path source=temporary.resolve("source");try(var pdf=new PDDocument()){pdf.addPage(new PDPage());pdf.save(source.toFile());}
        var result=render(source,"pdf","PDF");assertEquals("1",result.get("pages"));assertEquals("true",result.get("sourceReferenced"));assertEquals(sha(source),result.get("sha256"));
    }
    @Test void pageLimitIsChecked()throws Exception {
        Path source=temporary.resolve("source");try(var pdf=new PDDocument()){for(int i=0;i<201;i++)pdf.addPage(new PDPage());pdf.save(source.toFile());}
        rejects("PREVIEW_PAGE_LIMIT",source,"pdf","AUTO");
    }
    @Test void encryptedPdfNeverPromptsOrUsesAnImplicitPassword()throws Exception {
        Path source=temporary.resolve("source");
        try(var pdf=new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner-secret","reader-secret",new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            pdf.save(source.toFile());
        }
        rejects("PREVIEW_ENCRYPTED_UNSUPPORTED",source,"pdf","AUTO");
    }
    @Test void imagesBecomeSafePngAndMismatchedSuffixIsRejected()throws Exception {
        Path source=temporary.resolve("source");ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"PNG",source.toFile());
        var result=render(source,"png","AUTO");assertEquals("IMAGE",result.get("format"));assertEquals("image/png",result.get("mediaType"));assertEquals("false",result.get("sourceReferenced"));
        rejects("PREVIEW_TYPE_MISMATCH",source,"jpg","AUTO");
    }
    @Test void wrongOfficePackageAndActiveContentAreRejectedBeforeOfficeStarts()throws Exception {
        Path source=temporary.resolve("source");
        try(var out=new ZipOutputStream(Files.newOutputStream(source))){out.putNextEntry(new ZipEntry("[Content_Types].xml"));out.write("<Types/>".getBytes(StandardCharsets.UTF_8));out.closeEntry();}
        rejects("PREVIEW_TYPE_MISMATCH",source,"docx","AUTO");
        try(var out=new ZipOutputStream(Files.newOutputStream(source))){out.putNextEntry(new ZipEntry("word/vbaProject.bin"));out.write(1);out.closeEntry();}
        rejects("PREVIEW_ACTIVE_CONTENT_UNSUPPORTED",source,"docx","AUTO");
    }
    @Test void legacyOfficeDoesNotEnterAConverter()throws Exception {
        Path source=temporary.resolve("source");Files.writeString(source,"legacy");rejects("PREVIEW_FORMAT_UNSUPPORTED",source,"doc","AUTO");
    }
}
