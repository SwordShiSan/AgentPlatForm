package com.agentplatform.renderer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.sax.BodyContentHandler;
import org.xml.sax.ContentHandler;

/** Fixed, offline document-to-UTF-8 conversion inside the short-lived renderer only. */
final class DocumentTextExtractor {
    private static final long MAX_TEXT = 2L * 1024 * 1024;
    private static final Set<String> TEXT = Set.of("txt","md","csv","json","xml","yaml","yml","log","java","py","js","ts","css");
    private DocumentTextExtractor() {}

    static void extract(Path source, Path target, String format) throws Exception {
        if (!Set.of("docx","xlsx","pptx","pdf").contains(format) && !TEXT.contains(format))
            throw new PreviewRendererMain.Rejected("EXTRACTION_FORMAT_UNSUPPORTED");
        if (Set.of("docx","xlsx","pptx").contains(format)) PreviewRendererMain.officePackage(source, format);
        try {
            try (var output = new BoundedOutput(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW));
                 var writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
                if ("pdf".equals(format)) {
                    try (var pdf = Loader.loadPDF(source.toFile(), IOUtils.createTempFileOnlyStreamCache())) {
                        if (pdf.isEncrypted()) throw new PreviewRendererMain.Rejected("EXTRACTION_ENCRYPTED_UNSUPPORTED");
                        if (pdf.getNumberOfPages() < 1 || pdf.getNumberOfPages() > 200)
                            throw new PreviewRendererMain.Rejected("EXTRACTION_PAGE_LIMIT");
                        new PDFTextStripper().writeText(pdf, writer);
                    } catch (InvalidPasswordException encrypted) {
                        throw new PreviewRendererMain.Rejected("EXTRACTION_ENCRYPTED_UNSUPPORTED");
                    }
                } else if (TEXT.contains(format)) {
                    try (var reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
                        char[] chars = new char[8192]; int n;
                        while ((n = reader.read(chars)) != -1) {
                            for (int i = 0; i < n; i++) if (chars[i] == '\0')
                                throw new PreviewRendererMain.Rejected("EXTRACTION_TEXT_BINARY_REJECTED");
                            writer.write(chars, 0, n);
                        }
                    } catch (java.nio.charset.CharacterCodingException badEncoding) {
                        throw new PreviewRendererMain.Rejected("EXTRACTION_TEXT_ENCODING_UNSUPPORTED");
                    }
                } else {
                    var context = new ParseContext();
                    context.set(EmbeddedDocumentExtractor.class, new EmbeddedDocumentExtractor() {
                        @Override public boolean shouldParseEmbedded(Metadata ignored) { return false; }
                        @Override public void parseEmbedded(InputStream in, ContentHandler out, Metadata metadata, boolean html) { }
                    });
                    var metadata = new Metadata();
                    metadata.set("Content-Type", switch (format) {
                        case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                        case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                        default -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                    });
                    try (var in = Files.newInputStream(source)) {
                        var parser = new OOXMLParser();
                        parser.setExtractMacros(false);parser.setIncludeDeletedContent(false);
                        parser.setUseSAXDocxExtractor(true);parser.setUseSAXPptxExtractor(true);
                        parser.parse(in, new BodyContentHandler(writer), metadata, context);
                    }
                }
            }
            try (var reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
                int c; boolean text = false;
                while ((c = reader.read()) != -1) if (!Character.isWhitespace(c) && c != '\ufeff') { text = true; break; }
                if (!text) throw new PreviewRendererMain.Rejected("EXTRACTION_NO_TEXT");
            }
        } catch (Exception failure) {
            Files.deleteIfExists(target);
            for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause())
                if (cursor instanceof LimitExceeded) throw new PreviewRendererMain.Rejected("EXTRACTION_TEXT_TOO_LARGE");
            throw failure;
        }
    }
    private static final class LimitExceeded extends IOException { }
    private static final class BoundedOutput extends FilterOutputStream {
        private long count;
        BoundedOutput(OutputStream output) { super(output); }
        @Override public void write(int value) throws IOException {
            if (++count > MAX_TEXT) throw new LimitExceeded(); out.write(value);
        }
        @Override public void write(byte[] value, int offset, int length) throws IOException {
            if (length > MAX_TEXT - count) throw new LimitExceeded(); count += length; out.write(value, offset, length);
        }
    }
}
