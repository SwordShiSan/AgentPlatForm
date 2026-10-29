package com.agentplatform.renderer;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.jodconverter.local.LocalConverter;
import org.jodconverter.local.office.LocalOfficeManager;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

/** A single fixed invocation inside the disposable renderer. No API, database or credentials. */
public final class PreviewRendererMain {
    private static final long MAX_BYTES=104857600L, MAX_EXPANDED=524288000L;
    private static final int MAX_TEXT=2097152, MAX_PAGES=200, MAX_PIXELS=40000000;
    private static final Set<String> TEXT=Set.of("txt","md","csv","json","xml","yaml","yml","log","java","py","js","ts","css");
    private PreviewRendererMain() {}
    static final class Rejected extends Exception {
        final String code;
        Rejected(String code){super(code);this.code=code;}
    }
    public static void main(String[] args) {
        int exit=run(args);
        if(Files.isRegularFile(Path.of("/tmp/preview/manifest.properties"),LinkOption.NOFOLLOW_LINKS)) {
            // tmpfs survives only while the container is alive. The runner reads the immutable result,
            // closes the archive streams, and then stops this exact container before publishing it.
            System.out.println("PREVIEW_RESULT_READY");System.out.flush();
            try{Thread.sleep(130000L);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            return;
        }
        if(exit!=0)System.exit(exit);
    }
    static int run(String[] args) {
        Path output=null;
        try {
            if(args.length!=3)throw new Rejected("PREVIEW_ARGUMENT_REJECTED");
            String sourceFormat=args[0], requested=args[1], expectedHash=args[2];
            if(!sourceFormat.matches("[a-z0-9]{1,8}")||!Set.of("AUTO","PDF","IMAGE","HTML_STATIC","TEXT").contains(requested)
                    ||!expectedHash.matches("[a-f0-9]{64}"))throw new Rejected("PREVIEW_ARGUMENT_REJECTED");
            Path source=Path.of("/input/source."+sourceFormat);
            output=Path.of("/tmp/preview");Files.createDirectories(output);
            var descriptor=render(source,output,sourceFormat,requested,expectedHash);
            writeManifest(output,descriptor);return 0;
        }catch(Rejected invalid) {
            if(output!=null)try{writeManifest(output,Map.of("state","FAILED","errorCode",invalid.code,"retryable","false"));}catch(IOException ignored){}
            return 2;
        }catch(Throwable failed) {
            // Exception messages can contain input fragments or host library paths; publish a stable code only.
            System.err.println("PREVIEW_FAILURE_TYPE="+failed.getClass().getName());
            if(failed.getCause()!=null)System.err.println("PREVIEW_FAILURE_CAUSE_TYPE="+failed.getCause().getClass().getName());
            if(output!=null)try{writeManifest(output,Map.of("state","FAILED","errorCode","PREVIEW_CONVERSION_FAILED","retryable","false"));}catch(IOException ignored){}
            return 3;
        }
    }
    static Map<String,String> render(Path source,Path output,String ext,String requested,String expectedHash)throws Exception {
        if(Files.isSymbolicLink(source)||!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS))throw new Rejected("PREVIEW_INPUT_REJECTED");
        long bytes=Files.size(source);
        if(bytes>MAX_BYTES)throw new Rejected("PREVIEW_INPUT_TOO_LARGE");
        if(!hash(source).equals(expectedHash))throw new Rejected("PREVIEW_INPUT_HASH_MISMATCH");
        if("pdf".equals(ext)) {
            if(!Set.of("AUTO","PDF").contains(requested))throw new Rejected("PREVIEW_FORMAT_UNSUPPORTED");
            int pages=pdf(source);
            return success("PDF","application/pdf",source,bytes,expectedHash,pages,true);
        }
        if(Set.of("docx","xlsx","pptx").contains(ext)) {
            if(!Set.of("AUTO","PDF").contains(requested))throw new Rejected("PREVIEW_FORMAT_UNSUPPORTED");
            officePackage(source,ext);
            Path result=output.resolve("content.pdf");
            office(source,result,ext);
            int pages=pdf(result);
            return success("PDF","application/pdf",result,Files.size(result),hash(result),pages,false);
        }
        if(Set.of("png","jpg","jpeg","gif","webp").contains(ext)) {
            if(!Set.of("AUTO","IMAGE").contains(requested))throw new Rejected("PREVIEW_FORMAT_UNSUPPORTED");
            Path result=output.resolve("content.png");image(source,result,ext);
            return success("IMAGE","image/png",result,Files.size(result),hash(result),null,false);
        }
        if(TEXT.contains(ext)||Set.of("html","htm","svg").contains(ext)) {
            if(bytes>MAX_TEXT)throw new Rejected("PREVIEW_TEXT_TOO_LARGE");
            String text=text(source);
            if(Set.of("html","htm").contains(ext)&&"HTML_STATIC".equals(requested)) {
                Path result=output.resolve("content.html");
                Files.writeString(result,staticHtml(text),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                return success("HTML_STATIC","text/plain",result,Files.size(result),hash(result),null,false);
            }
            if(!Set.of("AUTO","TEXT").contains(requested))throw new Rejected("PREVIEW_FORMAT_UNSUPPORTED");
            return success("TEXT","text/plain",source,bytes,expectedHash,null,true);
        }
        throw new Rejected("PREVIEW_FORMAT_UNSUPPORTED");
    }
    private static Map<String,String> success(String format,String mime,Path content,long bytes,String hash,Integer pages,boolean source)throws Exception {
        if(bytes<0||bytes>MAX_BYTES||Files.isSymbolicLink(content)||!Files.isRegularFile(content,LinkOption.NOFOLLOW_LINKS))throw new Rejected("PREVIEW_OUTPUT_TOO_LARGE");
        var result=new TreeMap<String,String>();result.put("state","READY");result.put("format",format);result.put("mediaType",mime);
        result.put("bytes",Long.toString(bytes));result.put("sha256",hash);result.put("sourceReferenced",Boolean.toString(source));
        if(pages!=null)result.put("pages",pages.toString());if(!source)result.put("file",content.getFileName().toString());
        return result;
    }
    private static int pdf(Path input)throws Exception {
        if(Files.size(input)>MAX_BYTES)throw new Rejected("PREVIEW_OUTPUT_TOO_LARGE");
        try(var pdf=Loader.loadPDF(input.toFile(),IOUtils.createTempFileOnlyStreamCache())) {
            if(pdf.isEncrypted())throw new Rejected("PREVIEW_ENCRYPTED_UNSUPPORTED");
            int pages=pdf.getNumberOfPages();if(pages<1||pages>MAX_PAGES)throw new Rejected("PREVIEW_PAGE_LIMIT");return pages;
        }catch(org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException encrypted){throw new Rejected("PREVIEW_ENCRYPTED_UNSUPPORTED");}
    }
    private static void officePackage(Path source,String ext)throws Exception {
        String required=switch(ext){case "docx"->"word/document.xml";case "xlsx"->"xl/workbook.xml";default->"ppt/presentation.xml";};
        long expanded=0;boolean marker=false,types=false;int entries=0;var names=new HashSet<String>();
        try(var zip=new ZipInputStream(Files.newInputStream(source))) {
            byte[] buffer=new byte[65536];ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                String name=entry.getName();
                if(++entries>10000||!names.add(name)||name.startsWith("/")||name.indexOf('\\')>=0||Arrays.asList(name.split("/",-1)).contains(".."))throw new Rejected("PREVIEW_PACKAGE_REJECTED");
                String lower=name.toLowerCase(Locale.ROOT);
                if(lower.endsWith("vbaproject.bin")||lower.contains("/embeddings/")||lower.equals("encryptioninfo")||lower.equals("encryptedpackage"))throw new Rejected("PREVIEW_ACTIVE_CONTENT_UNSUPPORTED");
                marker|=required.equals(name);types|="[Content_Types].xml".equals(name);
                int n;while((n=zip.read(buffer))!=-1){expanded+=n;if(expanded>MAX_EXPANDED)throw new Rejected("PREVIEW_PACKAGE_TOO_LARGE");}
                zip.closeEntry();
            }
        }catch(ZipException invalid){throw new Rejected("PREVIEW_PACKAGE_REJECTED");}
        if(!marker||!types)throw new Rejected("PREVIEW_TYPE_MISMATCH");
    }
    private static void office(Path source,Path output,String extension)throws Exception {
        LocalOfficeManager manager=LocalOfficeManager.builder().officeHome("/opt/libreoffice26.2")
                .workingDir("/tmp/preview").templateProfileDir("/opt/platform/hardened-profile")
                .portNumbers(2002).maxTasksPerProcess(1).taskRetries(0).taskExecutionTimeout(110000L)
                .taskQueueTimeout(1000L).processTimeout(20000L).startFailFast(true).keepAliveOnShutdown(false).build();
        try {
            manager.start();
            LocalConverter.builder().officeManager(manager).loadProperty("MacroExecutionMode",com.sun.star.document.MacroExecMode.NEVER_EXECUTE).build()
                    .convert(source.toFile()).as(org.jodconverter.core.document.DefaultDocumentFormatRegistry.getInstance().getFormatByExtension(extension))
                    .to(output.toFile()).as(org.jodconverter.core.document.DefaultDocumentFormatRegistry.PDF).execute();
        }finally{manager.stop();}
    }
    private static void image(Path source,Path output,String extension)throws Exception {
        ImageIO.setUseCache(true);ImageIO.setCacheDirectory(output.getParent().toFile());
        try(ImageInputStream input=ImageIO.createImageInputStream(source.toFile())) {
            if(input==null)throw new Rejected("PREVIEW_IMAGE_REJECTED");
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new Rejected("PREVIEW_IMAGE_REJECTED");
            var reader=readers.next();
            try {
                reader.setInput(input,true,true);
                String detected=reader.getFormatName().toLowerCase(Locale.ROOT);
                if("jpg".equals(extension))extension="jpeg";
                if(!(detected.equals(extension)||"jpeg".equals(extension)&&"jpg".equals(detected)))throw new Rejected("PREVIEW_TYPE_MISMATCH");
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1||height<1||(long)width*height>MAX_PIXELS)throw new Rejected("PREVIEW_IMAGE_DIMENSION_LIMIT");
                BufferedImage bitmap=reader.read(0);
                try{if(!ImageIO.write(bitmap,"PNG",output.toFile()))throw new Rejected("PREVIEW_IMAGE_REJECTED");}
                finally{bitmap.flush();}
            }finally{reader.dispose();}
        }
    }
    private static String text(Path source)throws Exception {
        byte[] bytes=Files.readAllBytes(source); // Only the independently bounded <=2MiB text path.
        String value;
        try{value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException invalid){throw new Rejected("PREVIEW_TEXT_ENCODING_UNSUPPORTED");}
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c==0||c<32&&c!='\n'&&c!='\r'&&c!='\t'&&c!='\f')throw new Rejected("PREVIEW_TEXT_BINARY_REJECTED");}
        return value;
    }
    static String staticHtml(String text) {
        var safe=new Safelist().addTags("p","br","hr","div","span","h1","h2","h3","h4","h5","h6","strong","b","em","i","u","s","pre","code","blockquote","ul","ol","li","table","thead","tbody","tfoot","tr","th","td","caption").addAttributes("td","colspan","rowspan").addAttributes("th","colspan","rowspan");
        String content=Jsoup.clean(text,"",safe,new org.jsoup.nodes.Document.OutputSettings().prettyPrint(false));
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'none'; base-uri 'none'; form-action 'none'\"></head><body>"+content+"</body></html>";
    }
    private static void writeManifest(Path output,Map<String,String> values)throws IOException {
        Path pending=output.resolve("manifest.pending");
        try(var writer=Files.newBufferedWriter(pending,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW)) {
            for(var e:new TreeMap<>(values).entrySet())writer.write(e.getKey()+"="+e.getValue()+"\n");
        }
        Files.move(pending,output.resolve("manifest.properties"),StandardCopyOption.ATOMIC_MOVE);
    }
    private static String hash(Path path)throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(path)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
}
